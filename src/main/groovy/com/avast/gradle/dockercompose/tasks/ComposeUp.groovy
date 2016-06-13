package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExtension
import com.avast.gradle.dockercompose.ServiceHost
import com.avast.gradle.dockercompose.ServiceHostType
import com.avast.gradle.dockercompose.ServiceInfo
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecSpec
import org.yaml.snakeyaml.Yaml

import java.time.Instant

class ComposeUp extends DefaultTask {

    ComposeExtension extension
    ComposeDown downTask

    private Map<String, ServiceInfo> servicesInfos = [:]

    Map<String, ServiceInfo> getServicesInfos() {
        servicesInfos
    }

    ComposeUp() {
        group = 'docker'
        description = 'Builds and starts all containers of docker-compose project'
    }

    @TaskAction
    void up() {
        if (extension.buildBeforeUp) {
            project.exec { ExecSpec e ->
                e.environment = extension.environment
                e.commandLine extension.composeCommand('build')
            }
        }
        project.exec { ExecSpec e ->
            e.environment = extension.environment
            e.commandLine extension.composeCommand('up', '-d')
        }
        try {
            servicesInfos = loadServicesInfo().collectEntries { [(it.name): (it)] }
            if (extension.waitForTcpPorts) {
                waitForOpenTcpPorts(servicesInfos.values())
            }
        }
        catch (Exception e) {
            downTask.down()
            throw e
        }
    }

    protected Iterable<ServiceInfo> loadServicesInfo() {
        getServiceNames().collect { createServiceInfo(it) }
    }

    protected ServiceInfo createServiceInfo(String serviceName) {
        String containerId = getContainerId(serviceName)
        logger.info("Container ID of service $serviceName is $containerId")
        def inspection = getDockerInspection(containerId)
        ServiceHost host = getServiceHost(serviceName, inspection)
        logger.info("Will use $host as host of service $serviceName")
        def tcpPorts = getTcpPortsMapping(serviceName, inspection, host)
        new ServiceInfo(name: serviceName, serviceHost: host, tcpPorts: tcpPorts, containerHostname: inspection.Config.Hostname, inspection: inspection)
    }

    Iterable<String> getServiceNames() {
        String[] composeFiles = extension.useComposeFiles.empty ? ['docker-compose.yml', 'docker-compose.override.yml'] : extension.useComposeFiles
        composeFiles
            .findAll { project.file(it).exists() }
            .collectMany { composeFile ->
                def compose = (Map<String, Object>) (new Yaml().load(project.file(composeFile).text))
                // if there is 'version: 2' on top-level then information about services is in 'services' sub-tree
                '2'.equals(compose.get('version')) ? ((Map) compose.get('services')).keySet() : compose.keySet()
            }.unique()
    }

    String getContainerId(String serviceName) {
        new ByteArrayOutputStream().withStream { os ->
            project.exec { ExecSpec e ->
                e.environment = extension.environment
                e.commandLine extension.composeCommand('ps', '-q', serviceName)
                e.standardOutput = os
            }
            os.toString().trim()
        }
    }

    Map<String, Object> getDockerInspection(String containerId) {
        new ByteArrayOutputStream().withStream { os ->
            project.exec { ExecSpec e ->
                e.commandLine 'docker', 'inspect', containerId
                e.standardOutput os
            }
            def inspectionAsString = os.toString()
            logger.debug("Inspection for container $containerId: $inspectionAsString")
            (new Yaml().load(inspectionAsString))[0] as Map<String, Object>
        }
    }

    ServiceHost getServiceHost(String serviceName, Map<String, Object> inspection) {
        String dockerHost = System.getenv('DOCKER_HOST')
        if (dockerHost) {
            logger.debug("'DOCKER_HOST environment variable detected - will be used as hostname of service $serviceName'")
            new ServiceHost(host: dockerHost.toURI().host, type: ServiceHostType.RemoteDockerHost)
        } else if (isMac()) {
            // If running on Mac OS and DOCKER_HOST is not set, we can assume that
            // we are using Docker for Mac, in which case we should connect to localhost
            logger.debug("Will use localhost as host of $serviceName")
            new ServiceHost(host: 'localhost', type: ServiceHostType.LocalHost)
        } else {
            // read gateway of first containers network
            String gateway
            Map<String, Object> networkSettings = inspection.NetworkSettings
            Map<String, Object> networks = networkSettings.Networks
            if (networks && networks.every { it.key.toLowerCase().equals("host") }) {
                gateway = 'localhost'
                logger.debug("Will use $gateway as host of $serviceName because it is using HOST network")
            } else if (networks) {
                Map.Entry<String, Object> firstNetworkPair = networks.find()
                gateway = firstNetworkPair.value.Gateway
                logger.debug("Will use $gateway (network ${firstNetworkPair.key}) as host of $serviceName")
            } else { // networks not specified (older Docker versions)
                gateway = networkSettings.Gateway
                logger.debug("Will use $gateway as host of $serviceName")
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
            }
            else {
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
                    default:
                        throw new IllegalArgumentException("Unknown ServiceHostType '${host.type}' for service '$serviceName'")
                        break
                }
            }
        }
        ports
    }

    void waitForOpenTcpPorts(Iterable<ServiceInfo> servicesInfos) {
        servicesInfos.forEach { service ->
            service.tcpPorts.forEach { exposedPort, forwardedPort ->
                logger.lifecycle("Probing TCP socket on ${service.host}:${forwardedPort} of service '${service.name}'")
                def start = Instant.now()
                while (true) {
                    try {
                        def s = new Socket(service.host, forwardedPort)
                        s.close()
                        logger.lifecycle("TCP socket on ${service.host}:${forwardedPort} of service '${service.name}' is ready")
                        return
                    }
                    catch (Exception e) {
                        if (start.plus(extension.waitForTcpPortsTimeout) < Instant.now()) {
                            throw new RuntimeException("TCP socket on ${service.host}:${forwardedPort} of service '${service.name}' is still failing. Logs:${System.lineSeparator()}${getServiceLogs(service.name)}")
                        }
                        logger.lifecycle("Waiting for TCP socket on ${service.host}:${forwardedPort} of service '${service.name}' (${e.message})")
                        sleep(extension.waitAfterTcpProbeFailure.toMillis())
                    }
                }
            }
        }
    }

    String getServiceLogs(String serviceName) {
        def containerId = getContainerId(serviceName)
        new ByteArrayOutputStream().withStream { os ->
            project.exec { ExecSpec e ->
                e.commandLine 'docker', 'logs', '--follow=false', containerId
                e.standardOutput = os
            }
            os.toString().trim()
        }
    }

    private static boolean isMac() {
        System.getProperty("os.name").toLowerCase().startsWith("mac")
    }
}
