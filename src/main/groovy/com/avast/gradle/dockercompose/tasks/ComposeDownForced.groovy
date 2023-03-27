package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExecutor
import com.avast.gradle.dockercompose.RemoveImages
import com.avast.gradle.dockercompose.ServiceInfoCache
import com.avast.gradle.dockercompose.util.VersionNumber
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

import java.time.Duration

abstract class ComposeDownForced extends DefaultTask {

    @Internal
    abstract Property<Duration> getDockerComposeStopTimeout()

    @Internal
    abstract Property<Boolean> getRemoveContainers()

    @Internal
    abstract ListProperty<String> getStartedServices()

    @Internal
    abstract Property<Boolean> getRemoveVolumes()

    @Internal
    abstract Property<RemoveImages> getRemoveImages()

    @Internal
    abstract ListProperty<String> getDownAdditionalArgs()

    @Internal
    abstract RegularFileProperty getComposeLogToFile()
    
    @Internal
    abstract Property<String> getNestedName()

    @Internal
    abstract Property<ComposeExecutor> getComposeExecutor()

    @Internal
    abstract Property<ServiceInfoCache> getServiceInfoCache()

    ComposeDownForced() {
        group = 'docker'
        description = 'Stops and removes containers of docker-compose project'
    }

    @TaskAction
    void down() {
        def servicesToStop = composeExecutor.get().serviceNames
        serviceInfoCache.get().clear()
        composeExecutor.get().execute(*['stop', '--timeout', dockerComposeStopTimeout.get().getSeconds().toString(), *servicesToStop])
        if (removeContainers.get()) {
            if (composeExecutor.get().version >= VersionNumber.parse('1.6.0')) {
                String[] args = []
                if (!startedServices.get().empty) {
                    args += ['rm', '-f']
                    if (removeVolumes.get()) {
                        args += ['-v']
                    }
                    args += servicesToStop
                } else {
                    args += ['down']
                    switch (removeImages.get()) {
                        case RemoveImages.All:
                        case RemoveImages.Local:
                            args += ['--rmi', "${removeImages.get()}".toLowerCase()]
                            break
                        default:
                            break
                    }
                    if (removeVolumes.get()) {
                        args += ['--volumes']
                    }
                    if (composeExecutor.get().shouldRemoveOrphans()) {
                        args += '--remove-orphans'
                    }
                    args += downAdditionalArgs.get()
                }
                def composeLog = null
                if (composeLogToFile.isPresent()) {
                  File logFile = composeLogToFile.get().asFile
                  logger.debug "Logging docker-compose down to: $logFile"
                  logFile.parentFile.mkdirs()
                  composeLog = new FileOutputStream(logFile, true)
                }
                composeExecutor.get().executeWithCustomOutputWithExitValue(composeLog, args)
            } else {
                if (!startedServices.get().empty) {
                    composeExecutor.get().execute(*['rm', '-f', *servicesToStop])
                } else {
                    composeExecutor.get().execute('rm', '-f')
                }
            }
        }
    }
}
