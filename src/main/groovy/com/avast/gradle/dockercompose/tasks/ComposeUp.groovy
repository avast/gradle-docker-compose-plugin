package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeSettings
import com.avast.gradle.dockercompose.ContainerInfo
import com.avast.gradle.dockercompose.ServiceHost
import com.avast.gradle.dockercompose.ServiceInfo
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

import java.time.Instant

class ComposeUp extends DefaultTask {

    Boolean wasReconnected = false // for tests

    ComposeSettings settings

    private Map<String, ServiceInfo> servicesInfos = [:]

    Map<String, ServiceInfo> getServicesInfos() {
        servicesInfos
    }

    ComposeUp() {
        group = 'docker'
        description = 'Builds and starts containers of docker-compose project'
    }

    @TaskAction
    void up() {
        if (!settings.stopContainers) {
            def cachedServicesInfos = settings.serviceInfoCache.get({ getStateForCache() })
            if (cachedServicesInfos) {
                servicesInfos = cachedServicesInfos
                logger.lifecycle('Cached services infos loaded while \'stopContainers\' is set to \'false\'.')
                wasReconnected = true
                startCapturing()
                return
            }
        }
        settings.serviceInfoCache.clear()
        wasReconnected = false
        if (settings.buildBeforeUp) {
            settings.buildTask.build()
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
        if (settings.upAdditionalArgs) {
            args += settings.upAdditionalArgs
        }
        args += settings.startedServices
        try {
            def composeLog = null
            if(settings.composeLogToFile) {
              logger.debug "Logging docker-compose up to: ${settings.composeLogToFile}"
              settings.composeLogToFile.parentFile.mkdirs()
              composeLog = new FileOutputStream(settings.composeLogToFile)
            }
            settings.composeExecutor.executeWithCustomOutputWithExitValue(composeLog, args)
            startCapturing()
            def servicesToLoad = settings.startedServices ?: settings.composeExecutor.getServiceNames()
            servicesInfos = loadServicesInfo(servicesToLoad).collectEntries { [(it.name): (it)] }
            waitForHealthyContainers(servicesInfos.values())
            if (settings.waitForTcpPorts) {
                waitForOpenTcpPorts(servicesInfos.values())
            }
            if (!settings.stopContainers) {
                settings.serviceInfoCache.set(servicesInfos, getStateForCache())
            } else {
                settings.serviceInfoCache.clear()
            }
        }
        catch (Exception e) {
            logger.debug("Failed to start-up Docker containers", e)
            settings.downForcedTask.down()
            throw e
        }
    }

    protected void startCapturing() {
        if (settings.captureContainersOutput) {
            settings.composeExecutor.captureContainersOutput(logger.&lifecycle)
        }
        if (settings.captureContainersOutputToFile != null) {
            def logFile = settings.captureContainersOutputToFile
            logFile.parentFile.mkdirs()
            settings.composeExecutor.captureContainersOutput({ logFile.append(it + '\n') })
        }
    }

    protected def getStateForCache() {
        settings.composeExecutor.execute('ps')
    }

    protected Iterable<ServiceInfo> loadServicesInfo(Iterable<String> servicesNames) {
        // this code is little bit complicated - the aim is to execute `docker inspect` just once (for all the containers)
        Map<String, Iterable<String>> serviceToContainersIds = servicesNames.collectEntries { [(it) : settings.composeExecutor.getContainerIds(it)] }
        Map<String, Map<String, Object>> inspections = settings.dockerExecutor.getInspections(*serviceToContainersIds.values().flatten().unique())
        serviceToContainersIds.collect { pair -> new ServiceInfo(name: pair.key, containerInfos: pair.value.collectEntries { createContainerInfo(inspections.get(it), pair.key) } ) }
    }

    protected def createContainerInfo(Map<String, Object> inspection, String serviceName) {
        String containerId = inspection.Id
        logger.info("Container ID of service $serviceName is $containerId")
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

    void waitForHealthyContainers(Iterable<ServiceInfo> servicesInfos) {
        def start = Instant.now()
        servicesInfos.forEach { serviceInfo ->
            serviceInfo.containerInfos.each { instanceName, containerInfo ->
                def firstIteration = true
                while (true) {
                    def inspection = firstIteration ? containerInfo.inspection : settings.dockerExecutor.getInspection(containerInfo.containerId)
                    Map<String, Object> inspectionState = inspection.State
                    String healthStatus
                    if (inspectionState.containsKey('Health')) {
                        healthStatus = inspectionState.Health.Status
                        if (!"starting".equalsIgnoreCase(healthStatus) && !"unhealthy".equalsIgnoreCase(healthStatus)) {
                            logger.lifecycle("${instanceName} health state reported as '$healthStatus' - continuing...")
                            break
                        }
                        logger.lifecycle("Waiting for ${instanceName} to become healthy (it's $healthStatus)")
                        if (!firstIteration) sleep(settings.waitAfterHealthyStateProbeFailure.toMillis())
                    } else {
                        logger.debug("Service ${instanceName} or this version of Docker doesn't support healthchecks")
                        break
                    }
                    if (start.plus(settings.waitForHealthyStateTimeout) < Instant.now()) {
                        throw new RuntimeException("Container ${containerInfo.containerId} of service ${instanceName} is still reported as '${healthStatus}'. Logs:${System.lineSeparator()}${settings.dockerExecutor.getContainerLogs(containerInfo.containerId)}")
                    }
                    firstIteration = false
                }
            }
        }
    }

    void waitForOpenTcpPorts(Iterable<ServiceInfo> servicesInfos) {
        def start = Instant.now()
        servicesInfos.forEach { serviceInfo ->
            serviceInfo.containerInfos.each { instanceName, containerInfo ->
                containerInfo.tcpPorts
                .findAll { ep, fp -> !settings.tcpPortsToIgnoreWhenWaiting.any { it == ep } }
                .forEach { exposedPort, forwardedPort ->
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
                            break
                        }
                        catch (Exception e) {
                            if (start.plus(settings.waitForTcpPortsTimeout) < Instant.now()) {
                                throw new RuntimeException("TCP socket on ${containerInfo.host}:${forwardedPort} of service '${instanceName}' is still failing. Logs:${System.lineSeparator()}${settings.dockerExecutor.getContainerLogs(containerInfo.containerId)}")
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
