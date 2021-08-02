package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeSettings
import com.avast.gradle.dockercompose.RemoveImages
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.util.VersionNumber

class ComposeDownForced extends DefaultTask {
    @Internal
    ComposeSettings settings

    ComposeDownForced() {
        group = 'docker'
        description = 'Stops and removes containers of docker-compose project'
    }

    @TaskAction
    void down() {
        def servicesToStop = settings.composeExecutor.serviceNames
        settings.serviceInfoCache.clear()
        settings.composeExecutor.execute(*['stop', '--timeout', settings.dockerComposeStopTimeout.get().getSeconds().toString(), *servicesToStop])
        if (settings.removeContainers.get()) {
            if (settings.composeExecutor.version >= VersionNumber.parse('1.6.0')) {
                String[] args = []
                if (!settings.startedServices.get().empty) {
                    args += ['rm', '-f']
                    if (settings.removeVolumes.get()) {
                        args += ['-v']
                    }
                    args += servicesToStop
                } else {
                    args += ['down']
                    switch (settings.removeImages.get()) {
                        case RemoveImages.All:
                        case RemoveImages.Local:
                            args += ['--rmi', "${settings.removeImages.get()}".toLowerCase()]
                            break
                        default:
                            break
                    }
                    if (settings.removeVolumes.get()) {
                        args += ['--volumes']
                    }
                    if (settings.removeOrphans()) {
                        args += '--remove-orphans'
                    }
                    args += settings.downAdditionalArgs.get()
                }
                def composeLog = null
                if(settings.composeLogToFile.isPresent()) {
                  File logFile = settings.composeLogToFile.get().asFile
                  logger.debug "Logging docker-compose down to: $logFile"
                  logFile.parentFile.mkdirs()
                  composeLog = new FileOutputStream(logFile, true)
                }
                settings.composeExecutor.executeWithCustomOutputWithExitValue(composeLog, args)
            } else {
                if (!settings.startedServices.get().empty) {
                    settings.composeExecutor.execute(*['rm', '-f', *servicesToStop])
                } else {
                    settings.composeExecutor.execute('rm', '-f')
                }
            }
        }
    }
}
