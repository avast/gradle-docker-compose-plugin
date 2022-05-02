package com.avast.gradle.dockercompose


import groovy.transform.CompileStatic
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.JavaForkOptions
import org.gradle.process.ProcessForkOptions

import javax.inject.Inject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

@CompileStatic
abstract class ComposeSettings {
    transient final TasksConfigurator tasksConfigurator
    final DockerExecutor dockerExecutor

    abstract ListProperty<String> getUseComposeFiles()
    abstract ListProperty<String> getStartedServices()
    abstract Property<Boolean> getIncludeDependencies()
    abstract MapProperty<String, Integer> getScale()

    abstract ListProperty<String> getBuildAdditionalArgs()
    abstract ListProperty<String> getPullAdditionalArgs()
    abstract ListProperty<String> getUpAdditionalArgs()
    abstract ListProperty<String> getDownAdditionalArgs()
    abstract ListProperty<String> getComposeAdditionalArgs()

    abstract Property<Boolean> getBuildBeforeUp()
    abstract Property<Boolean> getBuildBeforePull()

    abstract Property<Boolean> getRemoveOrphans()
    abstract Property<Boolean> getForceRecreate()
    abstract Property<Boolean> getNoRecreate()

    abstract Property<Boolean> getStopContainers()
    abstract Property<Boolean> getRemoveContainers()
    abstract Property<Boolean> getRetainContainersOnStartupFailure()
    abstract Property<RemoveImages> getRemoveImages()
    abstract Property<Boolean> getRemoveVolumes()

    abstract Property<Boolean> getIgnorePullFailure()
    abstract Property<Boolean> getIgnorePushFailure()
    abstract ListProperty<String> getPushServices()

    abstract Property<Boolean> getWaitForTcpPorts()
    abstract ListProperty<Integer> getTcpPortsToIgnoreWhenWaiting()
    abstract Property<Duration> getWaitAfterTcpProbeFailure()
    abstract Property<Duration> getWaitForTcpPortsTimeout()
    abstract Property<Duration> getWaitForTcpPortsDisconnectionProbeTimeout()
    abstract Property<Duration> getWaitAfterHealthyStateProbeFailure()
    abstract Property<Duration> getWaitForHealthyStateTimeout()
    abstract Property<Boolean> getCheckContainersRunning()

    abstract Property<Boolean> getCaptureContainersOutput()
    abstract RegularFileProperty getCaptureContainersOutputToFile()
    abstract DirectoryProperty getCaptureContainersOutputToFiles()
    abstract RegularFileProperty getComposeLogToFile()
    abstract DirectoryProperty getContainerLogToDir()

    protected String customProjectName
    protected Boolean customProjectNameSet
    protected String safeProjectNamePrefix
    void setProjectName(String customProjectName)
    {
        this.customProjectName = customProjectName
        this.customProjectNameSet = true
    }
    String getProjectName() {
        if (customProjectNameSet) {
            return customProjectName
        }
        else if (projectNamePrefix) {
            return "${projectNamePrefix}_${nestedName}"
        }
        else {
            return "${safeProjectNamePrefix}_${nestedName}"
        }
    }
    String projectNamePrefix
    String nestedName

    abstract Property<String> getExecutable()
    abstract Property<String> getDockerExecutable()
    abstract MapProperty<String, Object> getEnvironment()

    abstract DirectoryProperty getDockerComposeWorkingDirectory()
    abstract Property<Duration> getDockerComposeStopTimeout()

    @Inject
    ComposeSettings(Project project, String name = '', String parentName = '') {
        this.nestedName = parentName + name
        this.safeProjectNamePrefix = generateSafeProjectNamePrefix(project)

        useComposeFiles.empty()
        startedServices.empty()
        includeDependencies.set(false)
        scale.empty()

        buildAdditionalArgs.empty()
        pullAdditionalArgs.empty()
        upAdditionalArgs.empty()
        downAdditionalArgs.empty()
        composeAdditionalArgs.empty()

        buildBeforeUp.set(true)
        buildBeforePull.set(true)

        removeOrphans.set(false)
        forceRecreate.set(false)
        noRecreate.set(false)

        stopContainers.set(true)
        removeContainers.set(true)
        retainContainersOnStartupFailure.set(false)
        removeImages.set(RemoveImages.None)
        removeVolumes.set(true)

        ignorePullFailure.set(false)
        ignorePushFailure.set(false)
        pushServices.empty()

        waitForTcpPorts.set(true)
        tcpPortsToIgnoreWhenWaiting.empty()
        waitAfterTcpProbeFailure.set(Duration.ofSeconds(1))
        waitForTcpPortsTimeout.set(Duration.ofMinutes(15))
        waitForTcpPortsDisconnectionProbeTimeout.set(Duration.ofMillis(1000))
        waitAfterHealthyStateProbeFailure.set(Duration.ofSeconds(5))
        waitForHealthyStateTimeout.set(Duration.ofMinutes(15))
        checkContainersRunning.set(true)

        captureContainersOutput.set(false)

        if (OperatingSystem.current().isMacOsX()) {
            // Default installation is inaccessible from path, so set sensible
            // defaults for this platform.
            executable.set('/usr/local/bin/docker-compose')
            dockerExecutable.set('/usr/local/bin/docker')
        } else {
            executable.set('docker-compose')
            dockerExecutable.set('docker')
        }
        dockerComposeStopTimeout.set(Duration.ofSeconds(10))

        this.containerLogToDir.set(project.buildDir.toPath().resolve('containers-logs').toFile())

        this.dockerExecutor = project.objects.newInstance(DockerExecutor, this)
        this.tasksConfigurator = new TasksConfigurator(this, project, name)
    }

    private static String generateSafeProjectNamePrefix(Project project) {
        def fullPathMd5 = MessageDigest.getInstance("MD5").digest(project.projectDir.absolutePath.toString().getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
        "${fullPathMd5}_${project.name.replace('.', '_')}"
    }

    protected ComposeSettings cloneAsNested(String name) {
        def r = tasksConfigurator.newComposeSettings(name, this.nestedName)

        r.includeDependencies.set(includeDependencies.get())

        r.buildAdditionalArgs.set(new ArrayList<String>(this.buildAdditionalArgs.get()))
        r.pullAdditionalArgs.set(new ArrayList<String>(this.pullAdditionalArgs.get()))
        r.upAdditionalArgs.set(new ArrayList<String>(this.upAdditionalArgs.get()))
        r.downAdditionalArgs.set(new ArrayList<String>(this.downAdditionalArgs.get()))
        r.composeAdditionalArgs.set(new ArrayList<String>(this.composeAdditionalArgs.get()))

        r.buildBeforeUp.set(this.buildBeforeUp.get())
        r.buildBeforePull.set(this.buildBeforePull.get())

        r.removeOrphans.set(this.removeOrphans.get())
        r.forceRecreate.set(this.forceRecreate.get())
        r.noRecreate.set(this.noRecreate.get())

        r.stopContainers.set(stopContainers.get())
        r.removeContainers.set(removeContainers.get())
        r.retainContainersOnStartupFailure.set(retainContainersOnStartupFailure.get())
        r.removeImages.set(removeImages.get())
        r.removeVolumes.set(removeVolumes.get())

        r.ignorePullFailure.set(ignorePullFailure.get())
        r.ignorePushFailure.set(ignorePushFailure.get())

        r.waitForTcpPorts.set(this.waitForTcpPorts.get())
        r.tcpPortsToIgnoreWhenWaiting.set(new ArrayList<Integer>(this.tcpPortsToIgnoreWhenWaiting.get()))
        r.waitAfterTcpProbeFailure.set(waitAfterTcpProbeFailure.get())
        r.waitForTcpPortsTimeout.set(waitForTcpPortsTimeout.get())
        r.waitForTcpPortsDisconnectionProbeTimeout.set(waitForTcpPortsDisconnectionProbeTimeout.get())
        r.waitAfterHealthyStateProbeFailure.set(waitAfterHealthyStateProbeFailure.get())
        r.waitForHealthyStateTimeout.set(waitForHealthyStateTimeout.get())
        r.checkContainersRunning.set(checkContainersRunning.get())

        r.captureContainersOutput.set(captureContainersOutput.get())

        r.projectNamePrefix = this.projectNamePrefix

        r.executable.set(this.executable.get())
        r.dockerExecutable.set(this.dockerExecutable.get())
        r.environment.set(new HashMap<String, Object>(this.environment.get()))

        r.dockerComposeWorkingDirectory.set(this.dockerComposeWorkingDirectory.getOrNull())
        r.dockerComposeStopTimeout.set(this.dockerComposeStopTimeout.get())
        r
    }

    void isRequiredBy(Task task) {
        tasksConfigurator.isRequiredByCore(task, false)
    }

    void isRequiredBy(TaskProvider<? extends Task> taskProvider) {
        taskProvider.configure { tasksConfigurator.isRequiredByCore(it, true) }
    }

    Map<String, ServiceInfo> getServicesInfos() {
        tasksConfigurator.getServicesInfos()
    }

    void exposeAsEnvironment(ProcessForkOptions task) {
        servicesInfos.values().each { serviceInfo ->
            serviceInfo.containerInfos.each { instanceName, si ->
                if (instanceName.endsWith('_1') || instanceName.endsWith('-1')) {
                    task.environment << createEnvironmentVariables(serviceInfo.name.toUpperCase(), si)
                }
                task.environment << createEnvironmentVariables(instanceName.toUpperCase(), si)
            }
        }
    }

    void exposeAsSystemProperties(JavaForkOptions task) {
        servicesInfos.values().each { serviceInfo ->
            serviceInfo.containerInfos.each { instanceName, si ->
                if(instanceName.endsWith('_1') || instanceName.endsWith('-1')) {
                    task.systemProperties << createSystemProperties(serviceInfo.name, si)
                }
                task.systemProperties << createSystemProperties(instanceName, si)
            }
        }
    }

    protected Map<String, Object> createEnvironmentVariables(String variableName, ContainerInfo ci) {
        def serviceName = replaceV2Separator(variableName)
        Map<String, Object> environmentVariables = [:]
        environmentVariables.put("${serviceName}_HOST".toString(), ci.host)
        environmentVariables.put("${serviceName}_CONTAINER_HOSTNAME".toString(), ci.containerHostname)
        ci.tcpPorts.each { environmentVariables.put("${serviceName}_TCP_${it.key}".toString(), it.value) }
        ci.udpPorts.each { environmentVariables.put("${serviceName}_UDP_${it.key}".toString(), it.value) }
        environmentVariables
    }

    protected Map<String, Object> createSystemProperties(String variableName, ContainerInfo ci) {
        def serviceName = replaceV2Separator(variableName)
        Map<String, Object> systemProperties = [:]
        systemProperties.put("${serviceName}.host".toString(), ci.host)
        systemProperties.put("${serviceName}.containerHostname".toString(), ci.containerHostname)
        ci.tcpPorts.each { systemProperties.put("${serviceName}.tcp.${it.key}".toString(), it.value) }
        ci.udpPorts.each { systemProperties.put("${serviceName}.udp.${it.key}".toString(), it.value) }
        systemProperties
    }

    static String replaceV2Separator(String serviceName) {
        serviceName.replaceAll('-(\\d+)$', '_$1')
    }
}

enum RemoveImages {
    None,
    Local, // images that don't have a custom name set by the `image` field
    All
}
