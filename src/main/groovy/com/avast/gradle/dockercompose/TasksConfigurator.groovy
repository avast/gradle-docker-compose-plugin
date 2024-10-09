package com.avast.gradle.dockercompose

import com.avast.gradle.dockercompose.tasks.*
import groovy.transform.CompileStatic
import groovy.transform.PackageScope
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.JavaForkOptions
import org.gradle.process.ProcessForkOptions

@CompileStatic
class TasksConfigurator {
    final ComposeSettings composeSettings
    final Project project
    final TaskProvider<ComposeUp> upTask
    final TaskProvider<ComposeDown> downTask
    final TaskProvider<ComposeDownForced> downForcedTask
    final TaskProvider<ComposeDownForced> downForcedOnFailureTask
    final TaskProvider<ComposeBuild> buildTask
    final TaskProvider<ComposePull> pullTask
    final TaskProvider<ComposeLogs> logsTask
    final TaskProvider<ComposePush> pushTask

    TasksConfigurator(ComposeSettings composeSettings, Project project, String name = '') {
        this.composeSettings = composeSettings
        this.project = project
        Provider<ComposeExecutor> composeExecutor = ComposeExecutor.getInstance(project, composeSettings)
        Provider<ServiceInfoCache> serviceInfoCache = ServiceInfoCache.getInstance(project, composeSettings.nestedName)
        this.downTask = project.tasks.register(name ? "${name}ComposeDown".toString() : 'composeDown', ComposeDown) {task ->
            configureDownForcedTask(task, composeExecutor, serviceInfoCache)
            task.stopContainers.set(composeSettings.stopContainers)
        }
        this.downForcedTask = project.tasks.register(name ? "${name}ComposeDownForced".toString() : 'composeDownForced', ComposeDownForced) {task ->
            configureDownForcedTask(task, composeExecutor, serviceInfoCache)
        }
        def downForcedOnFailureTask = project.tasks.register(name ? "${name}ComposeDownForcedOnFailure".toString() : 'composeDownForcedOnFailure', ComposeDownForced) {task ->
            configureDownForcedTask(task, composeExecutor, serviceInfoCache)
            task.onlyIf { task.serviceInfoCache.get().startupFailed }
        }
        this.downForcedOnFailureTask = downForcedOnFailureTask
        this.upTask = project.tasks.register(name ? "${name}ComposeUp".toString() : 'composeUp', ComposeUp) {task ->
            task.stopContainers.set(composeSettings.stopContainers)
            task.forceRecreate.set(composeSettings.forceRecreate)
            task.noRecreate.set(composeSettings.noRecreate)
            task.scale.set(composeSettings.scale)
            task.upAdditionalArgs.set(composeSettings.upAdditionalArgs)
            task.startedServices.set(composeSettings.startedServices)
            task.composeLogToFile.set(composeSettings.composeLogToFile)
            task.waitForTcpPorts.set(composeSettings.waitForTcpPorts)
            task.retainContainersOnStartupFailure.set(composeSettings.retainContainersOnStartupFailure)
            task.captureContainersOutput.set(composeSettings.captureContainersOutput)
            task.captureContainersOutputToFile.set(composeSettings.captureContainersOutputToFile)
            task.captureContainersOutputToFiles.set(composeSettings.captureContainersOutputToFiles)
            task.waitAfterHealthyStateProbeFailure.set(composeSettings.waitAfterHealthyStateProbeFailure)
            task.checkContainersRunning.set(composeSettings.checkContainersRunning)
            task.waitForHealthyStateTimeout.set(composeSettings.waitForHealthyStateTimeout)
            task.tcpPortsToIgnoreWhenWaiting.set(composeSettings.tcpPortsToIgnoreWhenWaiting)
            task.waitForTcpPortsDisconnectionProbeTimeout.set(composeSettings.waitForTcpPortsDisconnectionProbeTimeout)
            task.waitForTcpPortsTimeout.set(composeSettings.waitForTcpPortsTimeout)
            task.waitAfterTcpProbeFailure.set(composeSettings.waitAfterTcpProbeFailure)
            task.serviceInfoCache.set(serviceInfoCache)
            task.composeExecutor.set(composeExecutor)
            task.dependsOn(composeSettings.buildBeforeUp.map { buildBeforeUp ->
                buildBeforeUp ? [buildTask] : []
            })
            task.dockerExecutor = composeSettings.dockerExecutor
            task.finalizedBy(downForcedOnFailureTask)
            task.usesService(composeExecutor)
            task.usesService(serviceInfoCache)
        }
        this.buildTask = project.tasks.register(name ? "${name}ComposeBuild".toString() : 'composeBuild', ComposeBuild) {task ->
            task.buildAdditionalArgs.set(composeSettings.buildAdditionalArgs)
            task.startedServices.set(composeSettings.startedServices)
            task.composeExecutor.set(composeExecutor)
            task.usesService(composeExecutor)
        }
        this.pullTask = project.tasks.register(name ? "${name}ComposePull".toString() : 'composePull', ComposePull) {task ->
            task.ignorePullFailure.set(composeSettings.ignorePullFailure)
            task.pullAdditionalArgs.set(composeSettings.pullAdditionalArgs)
            task.startedServices.set(composeSettings.startedServices)
            task.composeExecutor.set(composeExecutor)
            task.dependsOn(composeSettings.buildBeforePull.map { buildBeforePull ->
                buildBeforePull ? [buildTask] : []
            })
            task.usesService(composeExecutor)
        }
        this.logsTask = project.tasks.register(name ? "${name}ComposeLogs".toString() : 'composeLogs', ComposeLogs) {task ->
            task.containerLogToDir.set(composeSettings.containerLogToDir)
            task.composeExecutor.set(composeExecutor)
            task.usesService(composeExecutor)
        }
        this.pushTask = project.tasks.register(name ? "${name}ComposePush".toString() : 'composePush', ComposePush) {task ->
            task.ignorePushFailure.set(composeSettings.ignorePushFailure)
            task.pushServices.set(composeSettings.pushServices)
            task.composeExecutor.set(composeExecutor)
            task.usesService(composeExecutor)
        }
    }

    private void configureDownForcedTask(
            ComposeDownForced task,
            Provider<ComposeExecutor> composeExecutor,
            Provider<ServiceInfoCache> serviceInfoCache
    ) {
        task.dockerComposeStopTimeout.set(composeSettings.dockerComposeStopTimeout)
        task.removeContainers.set(composeSettings.removeContainers)
        task.startedServices.set(composeSettings.startedServices)
        task.removeVolumes.set(composeSettings.removeVolumes)
        task.removeImages.set(composeSettings.removeImages)
        task.downAdditionalArgs.set(composeSettings.downAdditionalArgs)
        task.composeLogToFile.set(composeSettings.composeLogToFile)
        task.nestedName.set(composeSettings.nestedName)
        task.composeExecutor.set(composeExecutor)
        task.serviceInfoCache.set(serviceInfoCache)
        task.usesService(composeExecutor)
        task.usesService(serviceInfoCache)
    }

    @PackageScope
    void isRequiredByCore(Task task, boolean fromConfigure) {
        task.dependsOn upTask
        task.finalizedBy downTask
        def taskDependencies = getTaskDependencies(task)
        if (fromConfigure) {
            upTask.get().shouldRunAfter taskDependencies
        } else {
            upTask.configure { it.shouldRunAfter taskDependencies }
        }
        if (task instanceof ProcessForkOptions) task.doFirst { composeSettings.exposeAsEnvironment(task as ProcessForkOptions) }
        if (task instanceof JavaForkOptions) task.doFirst { composeSettings.exposeAsSystemProperties(task as JavaForkOptions) }
    }

    private TaskDependency getTaskDependencies(Task task) {
        def includedBuilds = task.project.gradle.includedBuilds
        if (includedBuilds.isEmpty()) {
            return task.taskDependencies
        } else {
            // Ignore any task dependencies from a composite/included build by delegating to a lazily filtered TaskDependency implementation
            // to avoid the "Cannot use shouldRunAfter to reference tasks from another build" error introduced in Gradle 8
            def includedBuildProjectNames = includedBuilds.collect { it.name }.toSet()
            return new TaskDependency() {
                Set<? extends Task> getDependencies(Task t) {
                    task.taskDependencies.getDependencies(t).findAll { dependency ->
                        // use rootProject.name in case the task is from a multi-module composite build
                        !includedBuildProjectNames.contains(dependency.project.rootProject.name)
                    }
                }
            }
        }
    }

    @PackageScope
    Map<String, ServiceInfo> getServicesInfos() {
        upTask.get().servicesInfos
    }

    @PackageScope
    void setupMissingRequiredBy(String taskName, ComposeSettings settings) {
        project.tasks
                .findAll { Task task -> task.name.equalsIgnoreCase(taskName) }
                .forEach { Task task -> settings.isRequiredBy(task) }
    }

    @PackageScope
    ComposeSettings newComposeSettings(String name, String nestedName) {
        return project.objects.newInstance(ComposeSettings, project, name, nestedName)
    }
}
