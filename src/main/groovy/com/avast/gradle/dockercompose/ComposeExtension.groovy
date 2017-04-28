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
    Duration waitAfterTcpProbeFailure = Duration.ofSeconds(1)
    Duration waitForTcpPortsTimeout = Duration.ofMinutes(15)
    Duration waitForTcpPortsDisconnectionProbeTimeout = Duration.ofMillis(1000)
    Duration waitAfterHealthyStateProbeFailure = Duration.ofSeconds(5)
    Duration waitForHealthyStateTimeout = Duration.ofMinutes(15)
    List<String> useComposeFiles = []
    String projectName = null

    boolean stopContainers = true
    boolean removeContainers = true
    RemoveImages removeImages = RemoveImages.None
    boolean removeVolumes = true

    String executable = 'docker-compose'
    Map<String, Object> environment = new HashMap<String, Object>(System.getenv());

    String dockerExecutable = 'docker'

    String dockerWorkingDirectory = null;

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
            .findAll { Task.class.isAssignableFrom(it.class) && ((Task)it).name.toLowerCase().contains('classes') }
            .each { ut.shouldRunAfter it }
    }

    Map<String, ServiceInfo> getServicesInfos() {
        upTask.servicesInfos
    }

    void exposeAsEnvironment(ProcessForkOptions task) {
        servicesInfos.values().each { si ->
            task.environment.put("${si.name.toUpperCase()}_HOST".toString(), si.host)
            task.environment.put("${si.name.toUpperCase()}_CONTAINER_HOSTNAME".toString(), si.containerHostname)
            si.tcpPorts.each {
                task.environment.put("${si.name.toUpperCase()}_TCP_${it.key}".toString(), it.value)
            }
        }
    }

    void exposeAsSystemProperties(JavaForkOptions task) {
        servicesInfos.values().each { si ->
            task.systemProperties.put("${si.name}.host".toString(), si.host)
            task.systemProperties.put("${si.name}.containerHostname".toString(), si.containerHostname)
            si.tcpPorts.each {
                task.systemProperties.put("${si.name}.tcp.${it.key}".toString(), it.value)
            }
        }
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
                if (dockerWorkingDirectory != null) {
                    e.setWorkingDir(dockerWorkingDirectory)
                }
                e.environment = env
                e.commandLine composeCommand('--version')
                e.standardOutput = os
            }
            VersionNumber.parse(os.toString().trim().findAll(/(\d+\.){2}(\d+)/).head())
        }
    }
}

enum RemoveImages {
    None,
    Local, // images that don't have a custom name set by the `image` field
    All
}
