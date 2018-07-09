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
        settings.serviceInfoCache.clear()
        settings.composeExecutor.execute(*['stop', '--timeout', settings.dockerComposeStopTimeout.getSeconds().toString(), *settings.startedServices])
        if (settings.removeContainers) {
            if (settings.composeExecutor.version >= VersionNumber.parse('1.6.0')) {
                String[] args = []
                if (!settings.startedServices.empty) {
                    args += ['rm', '-f']
                    if (settings.removeVolumes) {
                        args += ['-v']
                    }
                    args += settings.startedServices
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
                def composeLog = settings.composeLog ? new FileOutputStream(settings.composeLog) : null
                settings.composeExecutor.executeWithCustomOutput(composeLog, false, args)
            } else {
                if (!settings.startedServices.empty) {
                    settings.composeExecutor.execute(*['rm', '-f', *settings.startedServices])
                } else {
                    settings.composeExecutor.execute('rm', '-f')
                }
            }
        }
    }
}
