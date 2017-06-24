package com.avast.gradle.dockercompose

import com.avast.gradle.dockercompose.tasks.ComposeDown
import com.avast.gradle.dockercompose.tasks.ComposeUp
import groovy.transform.PackageScope
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.process.ExecSpec
import org.gradle.process.JavaForkOptions
import org.gradle.process.ProcessForkOptions
import org.gradle.util.VersionNumber

import java.time.Duration

class ComposeExtension {
    private final ComposeUp upTask
    private final ComposeDown downTask
    private final Project project

    boolean buildBeforeUp = true
    boolean waitForTcpPorts = true
    boolean captureContainersOutput = false
    File captureContainersOutputToFile = null
    Duration waitAfterTcpProbeFailure = Duration.ofSeconds(1)
    Duration waitForTcpPortsTimeout = Duration.ofMinutes(15)
    Duration waitForTcpPortsDisconnectionProbeTimeout = Duration.ofMillis(1000)
    Duration waitAfterHealthyStateProbeFailure = Duration.ofSeconds(5)
    Duration waitForHealthyStateTimeout = Duration.ofMinutes(15)
    List<String> useComposeFiles = []
    Map<String, Integer> scale = [:]
    String projectName = null

    boolean stopContainers = true
    boolean removeContainers = true
    RemoveImages removeImages = RemoveImages.None
    boolean removeVolumes = true
    boolean removeOrphans = false

    String executable = 'docker-compose'
    Map<String, Object> environment = new HashMap<String, Object>(System.getenv())

    String dockerExecutable = 'docker'

    String dockerComposeWorkingDirectory = null
    Duration dockerComposeStopTimeout = Duration.ofSeconds(10)

    ComposeExtension(Project project, ComposeUp upTask, ComposeDown downTask) {
        this.project = project
        this.downTask = downTask
        this.upTask = upTask
    }

    void isRequiredBy(Task task) {
        task.dependsOn upTask
        task.finalizedBy downTask
        def ut = upTask // to access private field from closure
        task.getTaskDependencies().getDependencies(task)
                .findAll { Task.class.isAssignableFrom(it.class) && ((Task) it).name.toLowerCase().contains('classes') }
                .each { ut.shouldRunAfter it }
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

    void setExecSpecWorkingDirectory(ExecSpec e) {
        if (dockerComposeWorkingDirectory != null) {
            e.setWorkingDir(dockerComposeWorkingDirectory)
        }
    }

    void setCaptureContainersOutputToFile(CharSequence path) {
        captureContainersOutputToFile = project.file(path)
    }

    void setCaptureContainersOutputToFile(File file) {
        captureContainersOutputToFile = file
    }

    /**
     * Composes docker-compose command, mainly adds '-f' and '-p' options.
     */
    @PackageScope
    Iterable<String> composeCommand(String... args) {
        def res = [executable]
        res.addAll(useComposeFiles.collectMany { ['-f', it] })
        if (projectName) {
            res.addAll(['-p', projectName])
        }
        res.addAll(args)
        res
    }

    @PackageScope
    Iterable<String> dockerCommand(String... args) {
        def res = [dockerExecutable]
        res.addAll(args)
        res
    }

    VersionNumber getDockerComposeVersion() {
        def p = this.project
        def env = this.environment
        new ByteArrayOutputStream().withStream { os ->
            p.exec { ExecSpec e ->
                setExecSpecWorkingDirectory(e)
                e.environment = env
                e.commandLine composeCommand('--version')
                e.standardOutput = os
            }
            VersionNumber.parse(os.toString().trim().findAll(/(\d+\.){2}(\d+)/).head())
        }
    }

    boolean removeOrphans() {
        dockerComposeVersion >= VersionNumber.parse('1.7.0') && this.removeOrphans
    }

    boolean scale() {
        if (dockerComposeVersion < VersionNumber.parse('1.13.0') && this.scale) {
            throw new UnsupportedOperationException("docker-compose version $dockerComposeVersion doesn't support --scale option")
        }
        this.scale
    }
}

enum RemoveImages {
    None,
    Local, // images that don't have a custom name set by the `image` field
    All
}
