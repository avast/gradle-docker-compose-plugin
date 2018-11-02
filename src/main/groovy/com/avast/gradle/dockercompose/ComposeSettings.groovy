package com.avast.gradle.dockercompose

import com.avast.gradle.dockercompose.tasks.ComposeBuild
import com.avast.gradle.dockercompose.tasks.ComposeDown
import com.avast.gradle.dockercompose.tasks.ComposeDownForced
import com.avast.gradle.dockercompose.tasks.ComposeLogs
import com.avast.gradle.dockercompose.tasks.ComposePull
import com.avast.gradle.dockercompose.tasks.ComposePush
import com.avast.gradle.dockercompose.tasks.ComposeUp
import com.avast.gradle.dockercompose.tasks.ServiceInfoCache
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.JavaForkOptions
import org.gradle.process.ProcessForkOptions
import org.gradle.util.VersionNumber

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

class ComposeSettings {
    final ComposeUp upTask
    final ComposeDown downTask
    final ComposeDownForced downForcedTask
    final ComposeBuild buildTask
    final ComposePull pullTask
    final ComposeLogs logsTask
    final ComposePush pushTask
    final Project project
    final DockerExecutor dockerExecutor
    final ComposeExecutor composeExecutor
    final ServiceInfoCache serviceInfoCache

    boolean buildBeforeUp = true
    boolean buildBeforePull = true
    boolean waitForTcpPorts = true
    List<Integer> tcpPortsToIgnoreWhenWaiting = []
    Duration waitAfterTcpProbeFailure = Duration.ofSeconds(1)
    Duration waitForTcpPortsTimeout = Duration.ofMinutes(15)
    Duration waitForTcpPortsDisconnectionProbeTimeout = Duration.ofMillis(1000)
    Duration waitAfterHealthyStateProbeFailure = Duration.ofSeconds(5)
    Duration waitForHealthyStateTimeout = Duration.ofMinutes(15)
    List<String> useComposeFiles = []

    boolean captureContainersOutput = false
    File captureContainersOutputToFile = null
    File composeLogToFile = null
    File containerLogToDir

    List<String> startedServices = []
    Map<String, Integer> scale = [:]
    boolean removeOrphans = false
    boolean forceRecreate = false
    List<String> buildAdditionalArgs = []
    List<String> pullAdditionalArgs = []
    List<String> upAdditionalArgs = []
    List<String> downAdditionalArgs = []
    String projectName

    boolean stopContainers = true
    boolean removeContainers = true
    RemoveImages removeImages = RemoveImages.None
    boolean removeVolumes = true

    boolean ignorePullFailure = false
    boolean ignorePushFailure = false
    List<String> pushServices = []

    String executable = 'docker-compose'
    Map<String, Object> environment = new HashMap<String, Object>(System.getenv())

    String dockerExecutable = 'docker'

    String dockerComposeWorkingDirectory = null
    Duration dockerComposeStopTimeout = Duration.ofSeconds(10)

    ComposeSettings(Project project, String name = '') {
        this.project = project

        upTask = project.tasks.create(name ? "${name}ComposeUp" : 'composeUp', ComposeUp)
        upTask.settings = this
        buildTask = project.tasks.create(name ? "${name}ComposeBuild" : 'composeBuild', ComposeBuild)
        buildTask.settings = this
        pullTask = project.tasks.create(name ? "${name}ComposePull" : 'composePull', ComposePull)
        pullTask.settings = this
        downTask = project.tasks.create(name ? "${name}ComposeDown" : 'composeDown', ComposeDown)
        downTask.settings = this
        downForcedTask = project.tasks.create(name ? "${name}ComposeDownForced" : 'composeDownForced', ComposeDownForced)
        downForcedTask.settings = this
        logsTask = project.tasks.create(name ? "${name}ComposeLogs" : 'composeLogs', ComposeLogs)
        logsTask.settings = this
        pushTask = project.tasks.create(name ? "${name}ComposePush" : 'composePush', ComposePush)
        pushTask.settings = this

        this.dockerExecutor = new DockerExecutor(this)
        this.composeExecutor = new ComposeExecutor(this)
        this.serviceInfoCache = new ServiceInfoCache(this)

        def fullPathMd5 = MessageDigest.getInstance("MD5").digest(project.projectDir.absolutePath.toString().getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
        this.projectName = fullPathMd5 + '_' + project.name + '_' + name

        this.containerLogToDir = project.buildDir.toPath().resolve('containers-logs').toFile()

        if (OperatingSystem.current().isMacOsX()) {
            // Default installation is inaccessible from path, so set sensible
            // defaults for this platform.
            this.executable = '/usr/local/bin/docker-compose'
            this.dockerExecutable = '/usr/local/bin/docker'
        }
    }

    ComposeSettings createNested(String name) {
        def r = new ComposeSettings(project, name)
        r.buildBeforeUp = this.buildBeforeUp
        r.buildBeforePull = this.buildBeforePull
        r.waitForTcpPorts = this.waitForTcpPorts
        r.tcpPortsToIgnoreWhenWaiting = new ArrayList<>(this.tcpPortsToIgnoreWhenWaiting)
        r.waitAfterTcpProbeFailure = this.waitAfterTcpProbeFailure
        r.waitForTcpPortsTimeout = this.waitForTcpPortsTimeout
        r.waitForTcpPortsDisconnectionProbeTimeout = this.waitForTcpPortsDisconnectionProbeTimeout
        r.waitAfterHealthyStateProbeFailure = this.waitAfterHealthyStateProbeFailure
        r.waitForHealthyStateTimeout = this.waitForHealthyStateTimeout

        r.captureContainersOutput = this.captureContainersOutput

        r.removeOrphans = this.removeOrphans
        r.forceRecreate = this.forceRecreate
        r.buildAdditionalArgs = new ArrayList<>(this.buildAdditionalArgs)
        r.pullAdditionalArgs = new ArrayList<>(this.pullAdditionalArgs)
        r.upAdditionalArgs = new ArrayList<>(this.upAdditionalArgs)
        r.downAdditionalArgs = new ArrayList<>(this.downAdditionalArgs)
        
        r.stopContainers = this.stopContainers
        r.removeContainers = this.removeContainers
        r.removeImages = this.removeImages
        r.removeVolumes = this.removeVolumes

        r.ignorePullFailure = this.ignorePullFailure
        r.ignorePushFailure = this.ignorePushFailure

        r.executable = this.executable
        r.environment = new HashMap<>(this.environment)

        r.dockerExecutable = this.dockerExecutable

        r.dockerComposeWorkingDirectory = this.dockerComposeWorkingDirectory
        r.dockerComposeStopTimeout = this.dockerComposeStopTimeout
        r
    }

    void isRequiredBy(Task task) {
        task.dependsOn upTask
        task.finalizedBy downTask
        def ut = upTask // to access private field from closure
        task.getTaskDependencies().getDependencies(task)
                .findAll { Task.class.isAssignableFrom(it.class) && ((Task) it).name.toLowerCase().contains('classes') }
                .each { ut.shouldRunAfter it }
        if (task instanceof ProcessForkOptions) task.doFirst { exposeAsEnvironment(task as ProcessForkOptions) }
        if (task instanceof JavaForkOptions) task.doFirst { exposeAsSystemProperties(task as JavaForkOptions) }
    }

    Map<String, ServiceInfo> getServicesInfos() {
        upTask.servicesInfos
    }

    void exposeAsEnvironment(ProcessForkOptions task) {
        servicesInfos.values().each { serviceInfo ->
            serviceInfo.containerInfos.each { instanceName, si ->
                if (instanceName.endsWith('_1')) {
                    task.environment << createEnvironmentVariables(serviceInfo.name.toUpperCase(), si)
                }
                task.environment << createEnvironmentVariables(instanceName.toUpperCase(), si)
            }
        }
    }

    void exposeAsSystemProperties(JavaForkOptions task) {
        servicesInfos.values().each { serviceInfo ->
            serviceInfo.containerInfos.each { instanceName, si ->
                if(instanceName.endsWith('_1')) {
                    task.systemProperties << createSystemProperties(serviceInfo.name, si)
                }
                task.systemProperties << createSystemProperties(instanceName, si)
            }
        }
    }

    protected Map<String, Object> createEnvironmentVariables(String variableName, ContainerInfo ci) {
        Map<String, Object> environmentVariables = [:]
        environmentVariables.put("${variableName}_HOST".toString(), ci.host)
        environmentVariables.put("${variableName}_CONTAINER_HOSTNAME".toString(), ci.containerHostname)
        ci.tcpPorts.each { environmentVariables.put("${variableName}_TCP_${it.key}".toString(), it.value) }
        environmentVariables
    }

    protected Map<String, Object> createSystemProperties(String variableName, ContainerInfo ci) {
        Map<String, Object> systemProperties = [:]
        systemProperties.put("${variableName}.host".toString(), ci.host)
        systemProperties.put("${variableName}.containerHostname".toString(), ci.containerHostname)
        ci.tcpPorts.each { systemProperties.put("${variableName}.tcp.${it.key}".toString(), it.value) }
        systemProperties
    }

    void setCaptureContainersOutputToFile(CharSequence path) {
        captureContainersOutputToFile = project.file(path)
    }

    void setCaptureContainersOutputToFile(File file) {
        captureContainersOutputToFile = file
    }

    void setComposeLogToFile(CharSequence path) {
        composeLogToFile = project.file(path)
    }

    void setComposeLogToFile(File file) {
        composeLogToFile = file
    }

    boolean removeOrphans() {
        composeExecutor.version >= VersionNumber.parse('1.7.0') && this.removeOrphans
    }

    boolean scale() {
        def v = composeExecutor.version
        if (v < VersionNumber.parse('1.13.0') && this.scale) {
            throw new UnsupportedOperationException("docker-compose version $v doesn't support --scale option")
        }
        this.scale
    }
}

enum RemoveImages {
    None,
    Local, // images that don't have a custom name set by the `image` field
    All
}
