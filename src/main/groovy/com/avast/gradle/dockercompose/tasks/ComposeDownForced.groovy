package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeSettings
import com.avast.gradle.dockercompose.RemoveImages
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.util.VersionNumber

class ComposeDownForced extends DefaultTask {
    ComposeSettings settings

    ComposeDownForced() {
        group = 'docker'
        description = 'Stops and removes containers of docker-compose project'
    }

    @TaskAction
    void down() {

        def dependentServices = []
        if(settings.includeDependencies) {
            if(settings.startedServices)
            {
                dependentServices = settings.composeExecutor.getDependentServices(
                        settings.startedServices).toList()
            }
        }

        def servicesToStop = [*settings.startedServices, *dependentServices].unique()

        settings.serviceInfoCache.clear()
        settings.composeExecutor.execute(*['stop', '--timeout', settings.dockerComposeStopTimeout.getSeconds().toString(), *servicesToStop])
        if (settings.removeContainers) {
            if (settings.composeExecutor.version >= VersionNumber.parse('1.6.0')) {
                String[] args = []
                if (!settings.startedServices.empty) {
                    args += ['rm', '-f']
                    if (settings.removeVolumes) {
                        args += ['-v']
                    }
                    args += settings.startedServices

                    if(settings.includeDependencies) {
                        if(dependentServices) {
                            args += dependentServices
                        }
                    }

                } else {
                    args += ['down']
                    switch (settings.removeImages) {
                        case RemoveImages.All:
                        case RemoveImages.Local:
                            args += ['--rmi', "${settings.removeImages}".toLowerCase()]
                            break
                        default:
                            break
                    }
                    if (settings.removeVolumes) {
                        args += ['--volumes']
                    }
                    if (settings.removeOrphans()) {
                        args += '--remove-orphans'
                    }
                    args += settings.downAdditionalArgs
                }
                def composeLog = null
                if(settings.composeLogToFile) {
                  logger.debug "Logging docker-compose down to: ${settings.composeLogToFile}"
                  settings.composeLogToFile.parentFile.mkdirs()
                  composeLog = new FileOutputStream(settings.composeLogToFile, true)
                }
                settings.composeExecutor.executeWithCustomOutputWithExitValue(composeLog, args)
            } else {
                if (!settings.startedServices.empty) {
                    settings.composeExecutor.execute(*['rm', '-f', *servicesToStop])
                } else {
                    settings.composeExecutor.execute('rm', '-f')
                }
            }
        }
    }
}
