package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExecutor
import com.avast.gradle.dockercompose.ContainerInfo
import com.avast.gradle.dockercompose.DockerExecutor
import com.avast.gradle.dockercompose.ServiceHost
import com.avast.gradle.dockercompose.ServiceInfo
import com.avast.gradle.dockercompose.ServiceInfoCache
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import java.time.Duration
import java.time.Instant

abstract class ComposeUp extends DefaultTask {

    @Internal
    Boolean wasReconnected = false // for tests

    @Internal
    DockerExecutor dockerExecutor

    @Internal
    abstract Property<Boolean> getStopContainers()

    @Internal
    abstract Property<Boolean> getForceRecreate()

    @Internal
    abstract Property<Boolean> getNoRecreate()

    @Internal
    abstract MapProperty<String, Integer> getScale()

    @Internal
    abstract ListProperty<String> getUpAdditionalArgs()

    @Internal
    abstract ListProperty<String> getStartedServices()

    @Internal
    abstract RegularFileProperty getComposeLogToFile()

    @Internal
    abstract Property<Boolean> getWaitForTcpPorts()

    @Internal
    abstract Property<Boolean> getRetainContainersOnStartupFailure()

    @Internal
    abstract Property<Boolean> getCaptureContainersOutput()

    @Internal
    abstract RegularFileProperty getCaptureContainersOutputToFile()

    @Internal
    abstract DirectoryProperty getCaptureContainersOutputToFiles()

    @Internal
    abstract Property<Duration> getWaitAfterHealthyStateProbeFailure()

    @Internal
    abstract Property<Boolean> getCheckContainersRunning()

    @Internal
    abstract Property<Duration> getWaitForHealthyStateTimeout()

    @Internal
    abstract ListProperty<Integer> getTcpPortsToIgnoreWhenWaiting()

    @Internal
    abstract Property<Duration> getWaitForTcpPortsDisconnectionProbeTimeout()

    @Internal
    abstract Property<Duration> getWaitForTcpPortsTimeout()

    @Internal
    abstract Property<Duration> getWaitAfterTcpProbeFailure()

    @Internal
    abstract Property<ServiceInfoCache> getServiceInfoCache()

    @Internal
    abstract Property<ComposeExecutor> getComposeExecutor()

    private Map<String, ServiceInfo> servicesInfos = [:]

    @Internal
    Map<String, ServiceInfo> getServicesInfos() {
        servicesInfos
    }

    ComposeUp() {
        group = 'docker'
        description = 'Builds and starts containers of docker-compose project'
    }

    @TaskAction
    void up() {
        if (!stopContainers.get()) {
            def cachedServicesInfos = serviceInfoCache.get().get({ getStateForCache() })
            if (cachedServicesInfos) {
                servicesInfos = cachedServicesInfos
                logger.lifecycle('Cached services infos loaded while \'stopContainers\' is set to \'false\'.')
                wasReconnected = true
                startCapturing()
                printExposedPorts()
                return
            }
        }
        serviceInfoCache.get().clear()
        wasReconnected = false
        String[] args = ['up', '-d']
        if (composeExecutor.get().shouldRemoveOrphans()) {
            args += '--remove-orphans'
        }
        if (forceRecreate.get()) {
            args += '--force-recreate'
            args += '--renew-anon-volumes'
        } else if (noRecreate.get()) {
            args += '--no-recreate'
        }
        if (composeExecutor.get().isScaleSupported()) {
            args += scale.get().collect { service, value ->
                ['--scale', "$service=$value".toString()]
            }.flatten()
        }
        args += upAdditionalArgs.get()
        args += startedServices.get()
        try {
            def composeLog = null
            if (composeLogToFile.isPresent()) {
              File logFile = composeLogToFile.get().asFile
              logger.debug "Logging docker-compose up to: $logFile"
              logFile.parentFile.mkdirs()
              composeLog = new FileOutputStream(logFile)
            }
            composeExecutor.get().executeWithCustomOutputWithExitValue(composeLog, args)
            def servicesToLoad = composeExecutor.get().getServiceNames()
            servicesInfos = loadServicesInfo(servicesToLoad).collectEntries { [(it.name): (it)] }
            startCapturing()
            waitForHealthyContainers(servicesInfos.values())
            if (waitForTcpPorts.get()) {
                servicesInfos = waitForOpenTcpPorts(servicesInfos.values()).collectEntries { [(it.name): (it)] }
            }
            printExposedPorts()
            if (!stopContainers.get()) {
                serviceInfoCache.get().set(servicesInfos, getStateForCache())
            } else {
                serviceInfoCache.get().clear()
            }
        }
        catch (Exception e) {
            logger.debug("Failed to start-up Docker containers", e)
            if (!retainContainersOnStartupFailure.get()) {
                serviceInfoCache.get().startupFailed = true
            }
            throw e
        }
    }

    protected void printExposedPorts() {
        if (!servicesInfos.values().any { si -> si.tcpPorts.any() }) {
            return
        }
        int nameMaxLength = Math.max('Name'.length(), servicesInfos.values().collect { it.containerInfos.values().collect { it.instanceName.length() } }.flatten().max())
        int containerPortMaxLenght = 'Container Port'.length()
        int mappingMaxLength = Math.max('Mapping'.length(), servicesInfos.values().collect { it.containerInfos.values().collect { ci -> ci.tcpPorts.collect { p -> "${ci.host}:${p.value}".length() } } }.flatten().max())
        logger.lifecycle('+-' + '-'.multiply(nameMaxLength) + '-+-' + '-'.multiply(containerPortMaxLenght) + '-+-' + '-'.multiply(mappingMaxLength) + '-+')
        logger.lifecycle('| Name' + ' '.multiply(nameMaxLength - 'Name'.length()) + ' | Container Port' + ' '.multiply(containerPortMaxLenght - 'Container Port'.length()) + ' | Mapping' + ' '.multiply(mappingMaxLength - 'Mapping'.length()) + ' |')
        logger.lifecycle('+-' + '-'.multiply(nameMaxLength) + '-+-' + '-'.multiply(containerPortMaxLenght) + '-+-' + '-'.multiply(mappingMaxLength) + '-+')
        servicesInfos.values().forEach { si ->
            if (si.containerInfos.values().any { it.tcpPorts.any() }) {
                si.containerInfos.values().forEach { ci ->
                    ci.tcpPorts.entrySet().forEach { p ->
                        String mapping = "${ci.host}:${p.value}".toString()
                        logger.lifecycle('| ' + ci.instanceName + ' '.multiply(nameMaxLength - ci.instanceName.length()) + ' | ' + p.key + ' '.multiply(containerPortMaxLenght - p.key.toString().length()) + ' | ' + mapping + ' '.multiply(mappingMaxLength - mapping.length()) + ' |')
                    }
                }
                logger.lifecycle('+-' + '-'.multiply(nameMaxLength) + '-+-' + '-'.multiply(containerPortMaxLenght) + '-+-' + '-'.multiply(mappingMaxLength) + '-+')
            }
        }
    }

    protected void startCapturing() {
        if (captureContainersOutput.get()) {
            composeExecutor.get().captureContainersOutput(logger.&lifecycle)
        }
        if (captureContainersOutputToFile.isPresent()) {
            def logFile = captureContainersOutputToFile.get().asFile
            logFile.parentFile.mkdirs()
            composeExecutor.get().captureContainersOutput({ logFile.append(it + '\n') })
        }
        if (captureContainersOutputToFiles.isPresent()) {
            def logDir = captureContainersOutputToFiles.get().asFile
            logDir.mkdirs()
            logDir.listFiles().each { it.delete() }
            servicesInfos.keySet().each {
                def logFile = logDir.toPath().resolve("${it}.log").toFile()
                composeExecutor.get().captureContainersOutput({ logFile.append(it + '\n') }, it)
            }
        }
    }

    private static final VOLATILE_STATE_KEYS = ['RunningFor']
    private static final UNSTABLE_ARRAY_STATE_KEYS = ['Mounts', 'Ports', 'Networks', 'Labels', 'Publishers']

    @Internal
    protected def getStateForCache() {
        String processesAsString = composeExecutor.get().execute('ps', '--format', 'json')
        String processesState = processesAsString
        try {
            // Since Docker Compose 2.21.0, the output is not one JSON array but newline-separated JSONs.
            Map<String, Object>[] processes
            if (processesAsString.startsWith('[')) {
                processes = new JsonSlurper().parseText(processesAsString)
            } else {
                processes = processesAsString.split('\\R').findAll { it.trim() }.collect { new JsonSlurper().parseText(it) }
            }
            List<Object> transformed = processes.collect {
                // Status field contains something like "Up 8 seconds", so we have to strip the duration.
                if (it.containsKey('Status') && it.Status.startsWith('Up ')) it.Status = 'Up'
                VOLATILE_STATE_KEYS.each { key -> it.remove(key) }
                UNSTABLE_ARRAY_STATE_KEYS.each { key -> it[key] = parseAndSortStateArray(it[key]) }
                it
            }
            processesState = transformed.join('\t')
        } catch (Exception e) {
            logger.warn("Cannot process JSON returned from 'docker compose ps --format json'", e)
        }
        processesState + composeExecutor.get().execute('config') + startedServices.get().join(',')
    }

    protected Object parseAndSortStateArray(Object list) {
        if (list instanceof List) {
            //Already provided as a List, no pre-parsing needed
            return list.sort { (first, second) -> first.toString() <=> second.toString() }
        } else if (list instanceof String && list.contains(",")) {
            //Not already a list, but a comma separated string
            return list.split(',').collect { it.trim() }.sort()
        } else {
            return list
        }
    }

    protected Iterable<ServiceInfo> loadServicesInfo(Iterable<String> servicesNames) {
        // this code is little bit complicated - the aim is to execute `docker inspect` just once (for all the containers)
        Map<String, Iterable<String>> serviceToContainersIds = composeExecutor.get().getContainerIds(servicesNames)
        Map<String, Map<String, Object>> inspections = dockerExecutor.getInspections(*serviceToContainersIds.values().flatten().unique())
        serviceToContainersIds.collect { pair -> new ServiceInfo(name: pair.key, containerInfos: pair.value.collect { createContainerInfo(inspections.get(it), pair.key) }.collectEntries { [(it.instanceName): it] } ) }
    }

    protected ContainerInfo createContainerInfo(Map<String, Object> inspection, String serviceName) {
        String containerId = inspection.Id
        logger.info("Container ID of service $serviceName is $containerId")
        ServiceHost host = dockerExecutor.getContainerHost(inspection, serviceName, logger)
        logger.info("Will use $host as host of service $serviceName")
        def tcpPorts = dockerExecutor.getTcpPortsMapping(serviceName, inspection, host)
        def udpPorts = dockerExecutor.getUdpPortsMapping(serviceName, inspection, host)
        // docker-compose v1 uses an underscore as a separator.  v2 uses a hyphen.
        String instanceName = inspection.Name.find(/${serviceName}_\d+$/) ?:
                inspection.Name.find(/${serviceName}-\d+$/) ?:
                inspection.Name - '/'
        new ContainerInfo(
                instanceName: instanceName,
                serviceHost: host,
                tcpPorts: tcpPorts,
                udpPorts: udpPorts,
                inspection: inspection)
    }

    void waitForHealthyContainers(Iterable<ServiceInfo> servicesInfos) {
        def start = Instant.now()
        servicesInfos.forEach { serviceInfo ->
            serviceInfo.containerInfos.each { instanceName, containerInfo ->
                def firstIteration = true
                while (true) {
                    def inspection = firstIteration ? containerInfo.inspection : dockerExecutor.getInspection(containerInfo.containerId)
                    Map<String, Object> inspectionState = inspection.State
                    String healthStatus
                    if (inspectionState.containsKey('Health')) {
                        healthStatus = inspectionState.Health.Status
                        if (!"starting".equalsIgnoreCase(healthStatus) && !"unhealthy".equalsIgnoreCase(healthStatus)) {
                            logger.lifecycle("${instanceName} health state reported as '$healthStatus' - continuing...")
                            break
                        }
                        logger.lifecycle("Waiting for ${instanceName} to become healthy (it's $healthStatus)")
                        if (!firstIteration) sleep(waitAfterHealthyStateProbeFailure.get().toMillis())
                    } else {
                        logger.debug("Service ${instanceName} or this version of Docker doesn't support healthchecks")
                        break
                    }
                    if (checkContainersRunning.get() && !"running".equalsIgnoreCase(inspectionState.Status) && !"restarting".equalsIgnoreCase(inspectionState.Status)) {
                        throw new RuntimeException("Container ${containerInfo.containerId} of ${instanceName} is not running nor restarting. Logs:${System.lineSeparator()}${dockerExecutor.getContainerLogs(containerInfo.containerId)}")
                    }
                    if (start.plus(waitForHealthyStateTimeout.get()) < Instant.now()) {
                        throw new RuntimeException("Container ${containerInfo.containerId} of ${instanceName} is still reported as '${healthStatus}'. Logs:${System.lineSeparator()}${dockerExecutor.getContainerLogs(containerInfo.containerId)}")
                    }
                    firstIteration = false
                }
            }
        }
    }

    Iterable<ServiceInfo> waitForOpenTcpPorts(Iterable<ServiceInfo> servicesInfos) {
        def start = Instant.now()
        Map<String, ContainerInfo> newContainerInfos = [:]
        servicesInfos.forEach { serviceInfo ->
            serviceInfo.containerInfos.each { instanceName, containerInfo ->
                containerInfo.tcpPorts
                .findAll { ep, fp -> !tcpPortsToIgnoreWhenWaiting.get().any { it == ep } }
                .forEach { exposedPort, forwardedPort ->
                    logger.lifecycle("Probing TCP socket on ${containerInfo.host}:${forwardedPort} of '${instanceName}'")
                    Integer portToCheck = forwardedPort
                    while (true) {
                        try {
                            def s = new Socket(containerInfo.host, portToCheck)
                            s.setSoTimeout(waitForTcpPortsDisconnectionProbeTimeout.get().toMillis() as int)
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
                                    throw new RuntimeException("TCP connection on ${containerInfo.host}:${portToCheck} of '${instanceName}' was disconnected right after connected")
                                }
                            }
                            finally {
                                s.close()
                            }
                            logger.lifecycle("TCP socket on ${containerInfo.host}:${portToCheck} of '${instanceName}' is ready")
                            break
                        }
                        catch (Exception e) {
                            if (start.plus(waitForTcpPortsTimeout.get()) < Instant.now()) {
                                throw new RuntimeException("TCP socket on ${containerInfo.host}:${portToCheck} of '${instanceName}' is still failing. Logs:${System.lineSeparator()}${dockerExecutor.getContainerLogs(containerInfo.containerId)}")
                            }
                            logger.lifecycle("Waiting for TCP socket on ${containerInfo.host}:${portToCheck} of '${instanceName}' (${e.message})")
                            sleep(waitAfterTcpProbeFailure.get().toMillis())
                            def inspection = dockerExecutor.getInspection(containerInfo.containerId)
                            if (checkContainersRunning.get() && !"running".equalsIgnoreCase(inspection.State.Status) && !"restarting".equalsIgnoreCase(inspection.State.Status)) {
                                throw new RuntimeException("Container ${containerInfo.containerId} of ${instanceName} is not running nor restarting. Logs:${System.lineSeparator()}${dockerExecutor.getContainerLogs(containerInfo.containerId)}")
                            }
                            ContainerInfo newContainerInfo = createContainerInfo(inspection, serviceInfo.name)
                            Integer newForwardedPort = newContainerInfo.tcpPorts.get(exposedPort)
                            if (newForwardedPort != portToCheck) {
                                logger.lifecycle("Going to replace container information of '${instanceName}' because port $exposedPort was exposed as $forwardedPort but is $newForwardedPort now")
                                newContainerInfos.put(instanceName, newContainerInfo)
                                portToCheck = newForwardedPort
                            }
                        }
                    }
                }
            }
        }
        servicesInfos.collect { it -> it.copyWith(containerInfos: it.containerInfos.values().collect { newContainerInfos.getOrDefault(it.instanceName, it) }.collectEntries { [(it.instanceName): it] }) }
    }
}
