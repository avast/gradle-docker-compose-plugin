package com.avast.gradle.dockercompose

import com.avast.gradle.dockercompose.tasks.ComposeBuild
import com.avast.gradle.dockercompose.tasks.ComposeDown
import com.avast.gradle.dockercompose.tasks.ComposeDownForced
import com.avast.gradle.dockercompose.tasks.ComposeLogs
import com.avast.gradle.dockercompose.tasks.ComposePull
import com.avast.gradle.dockercompose.tasks.ComposePush
import com.avast.gradle.dockercompose.tasks.ComposeUp
import com.avast.gradle.dockercompose.tasks.ServiceInfoCache
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.os.OperatingSystem
import org.gradle.process.JavaForkOptions
import org.gradle.process.ProcessForkOptions
import org.gradle.util.VersionNumber

import javax.inject.Inject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration

@CompileStatic
class ComposeSettings {
    final TaskProvider<ComposeUp> upTask
    final TaskProvider<ComposeDown> downTask
    final TaskProvider<ComposeDownForced> downForcedTask
    final TaskProvider<ComposeBuild> buildTask
    final TaskProvider<ComposePull> pullTask
    final TaskProvider<ComposeLogs> logsTask
    final TaskProvider<ComposePush> pushTask
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
    boolean checkContainersRunning = true

    List<String> useComposeFiles = []

    boolean captureContainersOutput = false
    File captureContainersOutputToFile = null
    File captureContainersOutputToFiles = null
    File composeLogToFile = null
    File containerLogToDir

    List<String> startedServices = []
    Map<String, Integer> scale = [:]
    boolean removeOrphans = false
    boolean forceRecreate = false
    boolean noRecreate = false
    List<String> buildAdditionalArgs = []
    List<String> pullAdditionalArgs = []
    List<String> upAdditionalArgs = []
    List<String> downAdditionalArgs = []
    List<String> composeAdditionalArgs = []

    protected String customProjectName
    protected Boolean customProjectNameSet
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
            return "${generateSafeProjectNamePrefix(project)}_${nestedName}"
        }
    }
    String projectNamePrefix
    String nestedName

    boolean stopContainers = true
    boolean removeContainers = true
    boolean retainContainersOnStartupFailure = false
    RemoveImages removeImages = RemoveImages.None
    boolean removeVolumes = true
    boolean includeDependencies = false

    boolean ignorePullFailure = false
    boolean ignorePushFailure = false
    List<String> pushServices = []

    String executable = 'docker-compose'
    Map<String, Object> environment = new HashMap<String, Object>(System.getenv())

    String dockerExecutable = 'docker'

    String dockerComposeWorkingDirectory = null
    Duration dockerComposeStopTimeout = Duration.ofSeconds(10)

    @Inject
    ComposeSettings(Project project, String name = '', String parentName = '') {
        this.project = project
        this.nestedName = parentName + name

        upTask = project.tasks.register(name ? "${name}ComposeUp".toString() : 'composeUp', ComposeUp, { it.settings = this })
        buildTask = project.tasks.register(name ? "${name}ComposeBuild".toString() : 'composeBuild', ComposeBuild, { it.settings = this })
        pullTask = project.tasks.register(name ? "${name}ComposePull".toString() : 'composePull', ComposePull, { it.settings = this })
        downTask = project.tasks.register(name ? "${name}ComposeDown".toString() : 'composeDown', ComposeDown, { it.settings = this })
        downForcedTask = project.tasks.register(name ? "${name}ComposeDownForced".toString() : 'composeDownForced', ComposeDownForced, { it.settings = this })
        logsTask = project.tasks.register(name ? "${name}ComposeLogs".toString() : 'composeLogs', ComposeLogs, { it.settings = this })
        pushTask = project.tasks.register(name ? "${name}ComposePush".toString() : 'composePush', ComposePush, { it.settings = this })

        this.dockerExecutor = project.objects.newInstance(DockerExecutor, this)
        this.composeExecutor = project.objects.newInstance(ComposeExecutor, this)
        this.serviceInfoCache = new ServiceInfoCache(this)

        this.containerLogToDir = project.buildDir.toPath().resolve('containers-logs').toFile()

        if (OperatingSystem.current().isMacOsX()) {
            // Default installation is inaccessible from path, so set sensible
            // defaults for this platform.
            this.executable = '/usr/local/bin/docker-compose'
            this.dockerExecutable = '/usr/local/bin/docker'
        }
    }

    private static String generateSafeProjectNamePrefix(Project project) {
        def fullPathMd5 = MessageDigest.getInstance("MD5").digest(project.projectDir.absolutePath.toString().getBytes(StandardCharsets.UTF_8)).encodeHex().toString()
        "${fullPathMd5}_${project.name}"
    }

    protected ComposeSettings cloneAsNested(String name) {
        def r = project.objects.newInstance(ComposeSettings, project, name, this.nestedName)
        r.buildBeforeUp = this.buildBeforeUp
        r.buildBeforePull = this.buildBeforePull

        r.waitForTcpPorts = this.waitForTcpPorts
        r.tcpPortsToIgnoreWhenWaiting = new ArrayList<>(this.tcpPortsToIgnoreWhenWaiting)
        r.waitAfterTcpProbeFailure = this.waitAfterTcpProbeFailure
        r.waitForTcpPortsTimeout = this.waitForTcpPortsTimeout
        r.waitForTcpPortsDisconnectionProbeTimeout = this.waitForTcpPortsDisconnectionProbeTimeout
        r.waitAfterHealthyStateProbeFailure = this.waitAfterHealthyStateProbeFailure
        r.waitForHealthyStateTimeout = this.waitForHealthyStateTimeout
        r.checkContainersRunning = this.checkContainersRunning

        r.captureContainersOutput = this.captureContainersOutput

        r.removeOrphans = this.removeOrphans
        r.forceRecreate = this.forceRecreate
        r.noRecreate = this.noRecreate
        r.buildAdditionalArgs = new ArrayList<>(this.buildAdditionalArgs)
        r.pullAdditionalArgs = new ArrayList<>(this.pullAdditionalArgs)
        r.upAdditionalArgs = new ArrayList<>(this.upAdditionalArgs)
        r.downAdditionalArgs = new ArrayList<>(this.downAdditionalArgs)
        r.composeAdditionalArgs = new ArrayList<>(this.composeAdditionalArgs)

        r.projectNamePrefix = this.projectNamePrefix

        r.stopContainers = this.stopContainers
        r.removeContainers = this.removeContainers
        r.retainContainersOnStartupFailure = this.retainContainersOnStartupFailure
        r.removeImages = this.removeImages
        r.removeVolumes = this.removeVolumes
        r.includeDependencies = this.includeDependencies

        r.ignorePullFailure = this.ignorePullFailure
        r.ignorePushFailure = this.ignorePushFailure

        r.executable = this.executable
        r.environment = new HashMap<>(this.environment)

        r.dockerExecutable = this.dockerExecutable

        r.dockerComposeWorkingDirectory = this.dockerComposeWorkingDirectory
        r.dockerComposeStopTimeout = this.dockerComposeStopTimeout
        r
    }

    @PackageScope
    void isRequiredByCore(Task task, boolean fromConfigure) {
        task.dependsOn upTask
        task.finalizedBy downTask
        project.tasks.findAll { Task.class.isAssignableFrom(it.class) && ((Task) it).name.toLowerCase().contains('classes') }
                .each { classesTask ->
                    if (fromConfigure) {
                        upTask.get().shouldRunAfter classesTask
                    } else {
                        upTask.configure { it.shouldRunAfter classesTask }
                    }
                }
        if (task instanceof ProcessForkOptions) task.doFirst { exposeAsEnvironment(task as ProcessForkOptions) }
        if (task instanceof JavaForkOptions) task.doFirst { exposeAsSystemProperties(task as JavaForkOptions) }
    }

    void isRequiredBy(Task task) {
        isRequiredByCore(task, false)
    }

    void isRequiredBy(TaskProvider<? extends Task> taskProvider) {
        taskProvider.configure { isRequiredByCore(it, true) }
    }

    Map<String, ServiceInfo> getServicesInfos() {
        upTask.get().servicesInfos
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
        ci.udpPorts.each { environmentVariables.put("${variableName}_UDP_${it.key}".toString(), it.value) }
        environmentVariables
    }

    protected Map<String, Object> createSystemProperties(String variableName, ContainerInfo ci) {
        Map<String, Object> systemProperties = [:]
        systemProperties.put("${variableName}.host".toString(), ci.host)
        systemProperties.put("${variableName}.containerHostname".toString(), ci.containerHostname)
        ci.tcpPorts.each { systemProperties.put("${variableName}.tcp.${it.key}".toString(), it.value) }
        ci.udpPorts.each { systemProperties.put("${variableName}.udp.${it.key}".toString(), it.value) }
        systemProperties
    }

    void setCaptureContainersOutputToFile(CharSequence path) {
        captureContainersOutputToFile = project.file(path)
    }

    void setCaptureContainersOutputToFile(File file) {
        captureContainersOutputToFile = file
    }

    void setCaptureContainersOutputToFiles(CharSequence path) {
        captureContainersOutputToFiles = project.file(path)
    }

    void setCaptureContainersOutputToFiles(File file) {
        captureContainersOutputToFiles = file
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
