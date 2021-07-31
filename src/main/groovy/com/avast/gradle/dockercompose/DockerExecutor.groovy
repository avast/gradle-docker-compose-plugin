package com.avast.gradle.dockercompose

import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
import org.yaml.snakeyaml.Yaml

import javax.inject.Inject

class DockerExecutor {
    private final ComposeSettings settings
    private final ExecOperations exec

    private static final Logger logger = Logging.getLogger(DockerExecutor.class);

    @Inject
    DockerExecutor(ComposeSettings settings, ExecOperations exec) {
        this.settings = settings
        this.exec = exec
    }

    String execute(String... args) {
        def exec = this.exec
        def settings = this.settings
        new ByteArrayOutputStream().withStream { os ->
            def er = exec.exec { ExecSpec e ->
                e.environment = settings.environment.get()
                def finalArgs = [settings.dockerExecutable.get()]
                finalArgs.addAll(args)
                e.commandLine finalArgs
                e.standardOutput = os
                e.ignoreExitValue = true
            }
            def stdout = os.toString().trim()
            if (er.exitValue != 0) {
                throw new RuntimeException("Exit-code ${er.exitValue} when calling ${settings.dockerExecutable.get()}, stdout: $stdout")
            }
            stdout
        }
    }

    List<String> getDockerInfo() {
        def asString = execute('info')
        logger.debug("Docker info: $asString")
        asString.readLines()
    }

    String getDockerPlatform() {
        String osType = getDockerInfo().collect { it.trim() }.find { it.startsWith('OSType:') }
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
        def asString = execute(*['inspect', *containersIds])
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

    ServiceHost getContainerHost(Map<String, Object> inspection, String serviceName, Logger logger = this.logger) {
        String servicesHost = settings.environment.get()['SERVICES_HOST'] ?: System.getenv('SERVICES_HOST')
        if (servicesHost) {
            logger.lifecycle("SERVICES_HOST environment variable detected - will be used as hostname of service $serviceName ($servicesHost)'")
            return new ServiceHost(host: servicesHost, type: ServiceHostType.RemoteDockerHost)
        }
        String dockerHost = settings.environment.get()['DOCKER_HOST'] ?: System.getenv('DOCKER_HOST')
        if (dockerHost) {
            def host = dockerHost.toURI().host ?: 'localhost'
            logger.lifecycle("DOCKER_HOST environment variable detected - will be used as hostname of service $serviceName ($host)'")
            return new ServiceHost(host: host, type: ServiceHostType.RemoteDockerHost)
        }
        Map<String, Object> networkSettings = inspection.NetworkSettings
        Map<String, Object> networks = networkSettings.Networks
        Map.Entry<String, Object> firstNetworkPair = networks.find()
        if (isWindows() && getContainerPlatform(inspection).toLowerCase().contains('win') && firstNetworkPair && "nat".equalsIgnoreCase(getNetworkDriver(firstNetworkPair.key))) {
            logger.lifecycle("Will use direct access to the container of $serviceName")
            return new ServiceHost(host: firstNetworkPair.value.IPAddress, type: ServiceHostType.DirectContainerAccess)
        }
        if (isMac() || isWindows()) {
            logger.lifecycle("Will use localhost as host of $serviceName")
            return new ServiceHost(host: 'localhost', type: ServiceHostType.LocalHost)
        }
        String networkMode = (String)inspection.HostConfig.NetworkMode ?: ''
        if (networkMode.startsWith('container:')) {
            String linkedContainerId = networkMode.substring('container:'.length())
            logger.lifecycle("Reading container host of $serviceName from linked container $linkedContainerId")
            return getContainerHost(getInspection(linkedContainerId), linkedContainerId, logger)
        }
        String gateway
        if (networks && networks.every { it.key.toLowerCase().equals("host") }) {
            gateway = 'localhost'
            logger.lifecycle("Will use $gateway as host of $serviceName because it is using HOST network")
            return new ServiceHost(host: gateway, type: ServiceHostType.Host)
        } else if (networks && networks.size() > 0) {
            gateway = firstNetworkPair.value.Gateway
            if (!gateway) {
                logger.lifecycle("Gateway cannot be read from container inspection - trying to read from network inspection (network '${firstNetworkPair.key}')")
                gateway = getNetworkGateway(firstNetworkPair.key)
            }
            logger.lifecycle("Will use $gateway (network ${firstNetworkPair.key}) as host of $serviceName")
            return new ServiceHost(host: gateway, type: ServiceHostType.NetworkGateway)
        }
        if (networkSettings.Gateway) { // networks not specified (older Docker versions)
            gateway = networkSettings.Gateway
            logger.lifecycle("Will use $gateway as host of $serviceName")
            return new ServiceHost(host: gateway, type: ServiceHostType.NetworkGateway)
        }
        logger.warn("Will use 'localhost' as host of $serviceName (as a fallback)")
        return new ServiceHost(host: 'localhost', type: ServiceHostType.LocalHost)
    }

    Map<Integer, Integer> getTcpPortsMapping(String serviceName, Map<String, Object> inspection, ServiceHost host) {
        getPortsMapping("TCP", serviceName, inspection, host)
    }

    Map<Integer, Integer> getUdpPortsMapping(String serviceName, Map<String, Object> inspection, ServiceHost host) {
        getPortsMapping("UDP", serviceName, inspection, host)
    }

    Map<Integer, Integer> getPortsMapping(String protocol, String serviceName, Map<String, Object> inspection, ServiceHost host) {
        Map<Integer, Integer> ports = [:]
        inspection.NetworkSettings.Ports.each { String exposedPortWithProtocol, forwardedPortsInfos ->
            def (String exposedPortAsString, String pr) = exposedPortWithProtocol.split('/')
            if (!protocol.equalsIgnoreCase(pr)) {
                return // from closure
            }
            int exposedPort = exposedPortAsString as int
            if (!forwardedPortsInfos || forwardedPortsInfos.isEmpty()) {
                logger.debug("No forwarded $protocol port for service '$serviceName:$exposedPort'")
            } else {
                switch (host.type) {
                    case ServiceHostType.LocalHost:
                    case ServiceHostType.NetworkGateway:
                    case ServiceHostType.RemoteDockerHost:
                        if (forwardedPortsInfos.size() > 1) {
                            logger.warn("More forwarded $protocol ports for service '$serviceName:$exposedPort $forwardedPortsInfos'. Will use the first one.")
                        }
                        def forwardedPortInfo = forwardedPortsInfos.first()
                        int forwardedPort = forwardedPortInfo.HostPort as int
                        logger.info("Exposed $protocol port on service '$serviceName:$exposedPort' will be available as $forwardedPort")
                        ports.put(exposedPort, forwardedPort)
                        break
                    case ServiceHostType.Host:
                        logger.info("Exposed $protocol port on service '$serviceName:$exposedPort' will be available as $exposedPort because it uses HOST network")
                        ports.put(exposedPort, exposedPort)
                        break;
                    case ServiceHostType.DirectContainerAccess:
                        logger.info("Exposed $protocol port on service '$serviceName:$exposedPort' will be available as $exposedPort because it uses direct access to the container")
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
