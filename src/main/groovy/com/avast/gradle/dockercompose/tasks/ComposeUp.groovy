package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.*
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

import java.time.Instant

class ComposeUp extends DefaultTask {

    ComposeSettings settings
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
        if (settings.buildBeforeUp) {
            settings.composeExecutor.execute('build', *settings.startedServices)
        }
        String[] args = ['up', '-d']
        if (settings.removeOrphans()) {
            args += '--remove-orphans'
        }
        if (settings.forceRecreate) {
            args += '--force-recreate'
        }
        if (settings.scale()) {
            args += settings.scale.collect { service, value ->
                ['--scale', "$service=$value"]
            }.flatten()
        }
        if (settings.exitCodeFromService) {
            args += ['--exit-code-from', settings.exitCodeFromService]
        }
        if (settings.upAdditionalArgs) {
            args += settings.upAdditionalArgs
        }
        args += settings.startedServices
        settings.composeExecutor.execute(args)
        try {
            if (settings.captureContainersOutput) {
                settings.composeExecutor.captureContainersOutput(logger.&lifecycle)
            }
            if (settings.captureContainersOutputToFile != null) {
                def logFile = settings.captureContainersOutputToFile
                logFile.parentFile.mkdirs()
                settings.composeExecutor.captureContainersOutput({ logFile.append(it + '\n') })
            }
            servicesInfos = loadServicesInfo().collectEntries { [(it.name): (it)] }
            waitForHealthyContainers(servicesInfos.values())
            if (settings.waitForTcpPorts) {
                waitForOpenTcpPorts(servicesInfos.values())
            }
        }
        catch (Exception e) {
            logger.debug("Failed to start-up Docker containers", e)
            downTask.down()
            throw e
        }
    }

    protected Iterable<ServiceInfo> loadServicesInfo() {
        settings.composeExecutor.getServiceNames().collect { createServiceInfo(it) }
    }

    protected ServiceInfo createServiceInfo(String serviceName) {
        Iterable<String> containerIds = settings.composeExecutor.getContainerIds(serviceName)
        Map<String, ContainerInfo> containerInfos = createContainerInfos(containerIds, serviceName)
        new ServiceInfo(name: serviceName, containerInfos: containerInfos)
    }

    Map<String, ContainerInfo> createContainerInfos(Iterable<String> containerIds, String serviceName) {
        containerIds.collectEntries { String containerId ->
            logger.info("Container ID of service $serviceName is $containerId")
            def inspection = settings.dockerExecutor.getValidDockerInspection(serviceName, containerId)
            ServiceHost host = settings.dockerExecutor.getContainerHost(inspection, serviceName, logger)
            logger.info("Will use $host as host of service $serviceName")
            def tcpPorts = settings.dockerExecutor.getTcpPortsMapping(serviceName, inspection, host)
            String instanceName = inspection.Name.find(/${serviceName}_\d+/) ?: inspection.Name - '/'
            [(instanceName): new ContainerInfo(
                    instanceName: instanceName,
                    serviceHost: host,
                    tcpPorts: tcpPorts,
                    inspection: inspection)]
        }
    }

    void waitForHealthyContainers(Iterable<ServiceInfo> servicesInfos) {
        def start = Instant.now()
        servicesInfos.forEach { serviceInfo ->
            serviceInfo.containerInfos.each { instanceName, containerInfo ->
                while (true) {
                    Map<String, Object> inspectionState = settings.dockerExecutor.getInspection(containerInfo.containerId).State
                    String healthStatus = 'undefined'
                    if (inspectionState.containsKey('Health')) {
                        healthStatus = inspectionState.Health.Status
                        if (!"starting".equalsIgnoreCase(healthStatus) && !"unhealthy".equalsIgnoreCase(healthStatus)) {
                            logger.lifecycle("${instanceName} health state reported as '$healthStatus' - continuing...")
                            return
                        }
                        logger.lifecycle("Waiting for ${instanceName} to become healthy (it's $healthStatus)")
                        sleep(settings.waitAfterHealthyStateProbeFailure.toMillis())
                    } else {
                        logger.debug("Service ${instanceName} or this version of Docker doesn't support healthchecks")
                        return
                    }
                    if (start.plus(settings.waitForHealthyStateTimeout) < Instant.now()) {
                        throw new RuntimeException("Container ${containerInfo.containerId} of service ${instanceName} is still reported as '${healthStatus}'. Logs:${System.lineSeparator()}${settings.dockerExecutor.getContainerLogs(containerInfo.containerId)}")
                    }
                }
            }
        }
    }

    void waitForOpenTcpPorts(Iterable<ServiceInfo> servicesInfos) {
        def start = Instant.now()
        servicesInfos.forEach { serviceInfo ->
            serviceInfo.containerInfos.each { instanceName, containerInfo ->
                containerInfo.tcpPorts.forEach { exposedPort, forwardedPort ->
                    logger.lifecycle("Probing TCP socket on ${containerInfo.host}:${forwardedPort} of service '${instanceName}'")
                    while (true) {
                        try {
                            def s = new Socket(containerInfo.host, forwardedPort)
                            s.setSoTimeout(settings.waitForTcpPortsDisconnectionProbeTimeout.toMillis() as int)
                            try {
                                // in case of Windows and Mac, we must ensure that the socket is not disconnected immediately
                                // if the socket is closed then it returns -1
                                // if the socket is not closed then returns a data or timeouts
                                boolean disconnected = false
                                try {
                                    disconnected = s.inputStream.read() == -1
                                } catch (Exception e) {
                                    logger.debug("An exception when reading from socket", e) // expected exception
                                }
                                if (disconnected) {
                                    throw new RuntimeException("TCP connection on ${containerInfo.host}:${forwardedPort} of service '${instanceName}' was disconnected right after connected")
                                }
                            }
                            finally {
                                s.close()
                            }
                            logger.lifecycle("TCP socket on ${containerInfo.host}:${forwardedPort} of service '${instanceName}' is ready")
                            return
                        }
                        catch (Exception e) {
                            if (start.plus(settings.waitForTcpPortsTimeout) < Instant.now()) {
                                throw new RuntimeException("TCP socket on ${containerInfo.host}:${forwardedPort} of service '${instanceName}' is still failing. Logs:${System.lineSeparator()}${settings.getContainerLogs(containerInfo.containerId)}")
                            }
                            logger.lifecycle("Waiting for TCP socket on ${containerInfo.host}:${forwardedPort} of service '${instanceName}' (${e.message})")
                            sleep(settings.waitAfterTcpProbeFailure.toMillis())
                        }
                    }
                }
            }
        }
    }
}
