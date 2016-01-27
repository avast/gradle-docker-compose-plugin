package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExtension
import com.avast.gradle.dockercompose.DockerHost
import com.avast.gradle.dockercompose.ServiceInfo
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecSpec
import org.yaml.snakeyaml.Yaml

class ComposeUp extends DefaultTask {

    private Map<String, ServiceInfo> servicesInfos = new HashMap<>()
    ComposeExtension extension

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
                e.commandLine 'docker-compose', 'build'
            }
        }
        project.exec { ExecSpec e ->
            e.commandLine 'docker-compose', 'up', '-d'
        }
        servicesInfos = loadServicesInfo().collectEntries { [(it.name): (it)] }
        if (extension.waitForTcpPorts) {
            waitForOpenTcpPorts(servicesInfos.values())
        }
    }

    protected Iterable<ServiceInfo> loadServicesInfo() {
        def services = new ArrayList<ServiceInfo>()
        def compose = (Map<String, Object>)(new Yaml().load(project.file('docker-compose.yml').text))
        compose.keySet().forEach { serviceName ->
            String containerId = getContainerId(serviceName)
            logger.info("Container ID of $serviceName is $containerId")
            def inspection = getDockerInspection(containerId)
            DockerHost host = getDockerHost(inspection)
            logger.info("Will use $host as host of $serviceName")
            def tcpPorts = getTcpPortsMapping(serviceName, inspection, host)
            services.add(new ServiceInfo(name: serviceName, dockerHost: host, tcpPorts: tcpPorts))
        }
        services
    }

    String getContainerId(String serviceName) {
        new ByteArrayOutputStream().withStream { os ->
            project.exec { ExecSpec e ->
                e.commandLine 'docker-compose', 'ps', '-q', serviceName
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
            (new Yaml().load(os.toString()))[0] as Map<String, Object>
        }
    }

    DockerHost getDockerHost(Map<String, Object> inspection) {
        String dockerHost = System.getenv('DOCKER_HOST')
        if (dockerHost) {
            new DockerHost(host: dockerHost.toURI().host, isRemote: true, containerHostname: inspection.Config.Hostname, inspection: inspection)
        } else {
            new DockerHost(host: inspection.NetworkSettings.IPAddress, isRemote: false, containerHostname: inspection.Config.Hostname, inspection: inspection)
        }
    }

    Map<Integer, Integer> getTcpPortsMapping(String serviceName, Map<String, Object> inspection, DockerHost host) {
        Map<Integer, Integer> ports = [:]
        inspection.NetworkSettings.Ports.each { String exposedPortWithProtocol, forwardedPortsInfos ->
            def (String exposedPortAsString, String protocol) = exposedPortWithProtocol.split('/')
            if (!"tcp".equalsIgnoreCase(protocol)) {
                return // from closure
            }
            int exposedPort = exposedPortAsString as int
            if (!forwardedPortsInfos || forwardedPortsInfos.isEmpty()) {
                logger.debug("No forwarded TCP port for $serviceName:$exposedPort")
            }
            else if (host.isRemote) {
                if (forwardedPortsInfos.size() > 1) {
                    logger.warn("More forwarded TCP ports for $serviceName:$exposedPort $forwardedPortsInfos Will use the first one.")
                }
                def forwardedPortInfo = forwardedPortsInfos.first()
                int forwardedPort = forwardedPortInfo.HostPort as int
                logger.info("Exposed TCP port $serviceName:$exposedPort will be available as $forwardedPort")
                ports.put(exposedPort, forwardedPort)
            } else {
                ports.put(exposedPort, exposedPort)
                logger.info("Exposed TCP port $serviceName:$exposedPort will be available as the same port because we connect to the container directly")
            }
        }
        ports
    }

    void waitForOpenTcpPorts(Iterable<ServiceInfo> servicesInfos) {
        servicesInfos.forEach { service ->
            service.tcpPorts.forEach { exposedPort, forwardedPort ->
                logger.lifecycle("Probing TCP socket on ${service.host}:${forwardedPort} of ${service.name}")
                while (true) {
                    try {
                        def s = new Socket(service.host, forwardedPort)
                        s.close()
                        logger.lifecycle("TCP socket on ${service.host}:${forwardedPort} of ${service.name} is ready")
                        return
                    }
                    catch (Exception e) {
                        logger.lifecycle("Waiting for TCP socket on ${service.host}:${forwardedPort} of ${service.name} (${e.message})")
                        sleep(extension.waitAfterTcpProbeFailure.toMillis())
                    }
                }
            }
        }
    }
}
