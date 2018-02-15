package com.avast.gradle.dockercompose

import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.process.ExecSpec
import org.yaml.snakeyaml.Yaml

class DockerExecutor {
    private final ComposeSettings settings
    private final Project project
    private final Logger logger

    DockerExecutor(ComposeSettings settings) {
        this.settings = settings
        this.project = settings.project
        this.logger = settings.project.logger
    }

    String execute(String... args) {
        new ByteArrayOutputStream().withStream { os ->
            project.exec { ExecSpec e ->
                e.environment = settings.environment
                def finalArgs = [settings.dockerExecutable]
                finalArgs.addAll(args)
                e.commandLine finalArgs
                e.standardOutput os
            }
            os.toString().trim()
        }
    }

    List<String> getDockerInfo() {
        def asString = execute('info')
        logger.debug("Docker info: $asString")
        asString.readLines()
    }

    String getDockerPlatform() {
        String osType = getDockerInfo().find { it.startsWith('OSType:') }
        osType.empty ? System.getProperty("os.name") : osType.substring('OSType:'.length()).trim()
    }

    String getContainerPlatform(Map<String, Object> inspection) {
        def platform = inspection.Platform as String
        platform ?: getDockerPlatform()
    }

    Map<String, Object> getInspection(String containerId) {
        getInspections(containerId).values().find()
    }

    Map<String, Map<String, Object>> getInspections(String... containersIds) {
        def asString = execute('inspect', *containersIds)
        logger.debug("Inspections for containers ${containersIds.join(', ')}: $asString")
        Map<String, Object>[] inspections = new Yaml().load(asString)
        def r = inspections.collectEntries { [it.Id, it] }
        def notFoundInspections = containersIds.findAll { !r.containsKey(it) }
        if (notFoundInspections) {
            throw new RuntimeException('docker inspect didn\'t return inspection for these containers: ' + notFoundInspections.join(', '))
        }
        r
    }

    Map<String, Object> getNetworkInspection(String networkName) {
        def asString = execute('network', 'inspect', networkName)
        logger.debug("Inspection for network $networkName: $asString")
        (new Yaml().load(asString))[0] as Map<String, Object>
    }

    String getNetworkGateway(String networkName) {
        def networkInspection = getNetworkInspection(networkName)
        if (networkInspection) {
            Map<String, Object> ipam = networkInspection.IPAM
            if (ipam) {
                Map<String, Object>[] ipamConfig = ipam.Config
                if (ipamConfig && ipamConfig.size() > 0) {
                    return ipamConfig[0].Gateway
                }
            }
        }
        null
    }

    String getNetworkDriver(String networkName) {
        def networkInspection = getNetworkInspection(networkName)
        networkInspection ? networkInspection.Driver as String : ""
    }

    String getContainerLogs(String containerId) {
        execute('logs', '--follow=false', containerId)
    }

    void validateInspection(String serviceName, Map<String, Object> inspection) {
        ServiceHost serviceHost
        try {
            serviceHost = getContainerHost(inspection, serviceName, NoOpLogger.INSTANCE)
        } catch (Exception e) {
            throw new RuntimeException("Error when getting service host of service $serviceName: ${e.message}\n${inspection.toString()}", e)
        }
        if (serviceHost.type != ServiceHostType.Host) {
            Map<String, Object> portsFromConfig = inspection.Config.ExposedPorts ?: [:]
            Map<String, Object> portsFromNetwork = inspection.NetworkSettings.Ports
            def missingPorts = portsFromConfig.keySet().findAll { !portsFromNetwork.containsKey(it) }
            if (!missingPorts.empty) {
                throw new RuntimeException("These ports of service $serviceName are declared as exposed but cannot be found in NetworkSettings: ${missingPorts.join(', ')}\\n${inspection.toString()}")
            }
        }
    }

    ServiceHost getContainerHost(Map<String, Object> inspection, String serviceName, Logger logger = this.logger) {
        String servicesHost = settings.environment['SERVICES_HOST'] ?: System.getenv('SERVICES_HOST')
        if (servicesHost) {
            logger.lifecycle("SERVICES_HOST environment variable detected - will be used as hostname of service $serviceName ($servicesHost)'")
            return new ServiceHost(host: servicesHost, type: ServiceHostType.RemoteDockerHost)
        }
        Map<String, Object> networkSettings = inspection.NetworkSettings
        Map<String, Object> networks = networkSettings.Networks
        Map.Entry<String, Object> firstNetworkPair = networks.find()
        String dockerHost = settings.environment['DOCKER_HOST'] ?: System.getenv('DOCKER_HOST')
        if (dockerHost) {
            def host = dockerHost.toURI().host ?: 'localhost'
            logger.lifecycle("DOCKER_HOST environment variable detected - will be used as hostname of service $serviceName ($host)'")
            new ServiceHost(host: host, type: ServiceHostType.RemoteDockerHost)
        } else if (isWindows() && getContainerPlatform(inspection).toLowerCase().contains('win') && "nat".equalsIgnoreCase(getNetworkDriver(firstNetworkPair.key))) {
            logger.lifecycle("Will use direct access to the container of $serviceName")
            return new ServiceHost(host: firstNetworkPair.value.IPAddress, type: ServiceHostType.DirectContainerAccess)
        } else if (isMac() || isWindows()) {
            logger.lifecycle("Will use localhost as host of $serviceName")
            new ServiceHost(host: 'localhost', type: ServiceHostType.LocalHost)
        } else {
            // read gateway of first containers network
            String gateway
            if (networks && networks.every { it.key.toLowerCase().equals("host") }) {
                gateway = 'localhost'
                logger.lifecycle("Will use $gateway as host of $serviceName because it is using HOST network")
                return new ServiceHost(host: 'localhost', type: ServiceHostType.Host)
            } else if (networks && networks.size() > 0) {
                gateway = firstNetworkPair.value.Gateway
                if (!gateway) {
                    logger.lifecycle("Gateway cannot be read from container inspection - trying to read from network inspection (network '${firstNetworkPair.key}')")
                    gateway = getNetworkGateway(firstNetworkPair.key)
                }
                logger.lifecycle("Will use $gateway (network ${firstNetworkPair.key}) as host of $serviceName")
            } else { // networks not specified (older Docker versions)
                gateway = networkSettings.Gateway
                logger.lifecycle("Will use $gateway as host of $serviceName")
            }
            if (!gateway) {
                throw new RuntimeException('Gateway cannot be obtained')
            }
            new ServiceHost(host: gateway, type: ServiceHostType.NetworkGateway)
        }
    }

    Map<Integer, Integer> getTcpPortsMapping(String serviceName, Map<String, Object> inspection, ServiceHost host) {
        Map<Integer, Integer> ports = [:]
        inspection.NetworkSettings.Ports.each { String exposedPortWithProtocol, forwardedPortsInfos ->
            def (String exposedPortAsString, String protocol) = exposedPortWithProtocol.split('/')
            if (!"tcp".equalsIgnoreCase(protocol)) {
                return // from closure
            }
            int exposedPort = exposedPortAsString as int
            if (!forwardedPortsInfos || forwardedPortsInfos.isEmpty()) {
                logger.debug("No forwarded TCP port for service '$serviceName:$exposedPort'")
            } else {
                switch (host.type) {
                    case ServiceHostType.LocalHost:
                    case ServiceHostType.NetworkGateway:
                    case ServiceHostType.RemoteDockerHost:
                        if (forwardedPortsInfos.size() > 1) {
                            logger.warn("More forwarded TCP ports for service '$serviceName:$exposedPort $forwardedPortsInfos'. Will use the first one.")
                        }
                        def forwardedPortInfo = forwardedPortsInfos.first()
                        int forwardedPort = forwardedPortInfo.HostPort as int
                        logger.info("Exposed TCP port on service '$serviceName:$exposedPort' will be available as $forwardedPort")
                        ports.put(exposedPort, forwardedPort)
                        break
                    case ServiceHostType.Host:
                        logger.info("Exposed TCP port on service '$serviceName:$exposedPort' will be available as $exposedPort because it uses HOST network")
                        ports.put(exposedPort, exposedPort)
                        break;
                    case ServiceHostType.DirectContainerAccess:
                        logger.info("Exposed TCP port on service '$serviceName:$exposedPort' will be available as $exposedPort because it uses direct access to the container")
                        ports.put(exposedPort, exposedPort)
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown ServiceHostType '${host.type}' for service '$serviceName'")
                        break
                }
            }
        }
        ports
    }

    private static boolean isMac() {
        System.getProperty("os.name").toLowerCase().startsWith("mac")
    }

    private static boolean isWindows() {
        System.getProperty("os.name").toLowerCase().startsWith("win")
    }
}
