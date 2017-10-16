package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExtension
import com.avast.gradle.dockercompose.RemoveImages
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecSpec
import org.gradle.util.VersionNumber

class ComposeDown extends DefaultTask {
    ComposeExtension extension

    ComposeDown() {
        group = 'docker'
        description = 'Stops and removes all containers of docker-compose project'
    }

    @TaskAction
    void down() {
        if (extension.stopContainers) {
            extension.composeExecutor.execute('stop', '--timeout', extension.dockerComposeStopTimeout.getSeconds().toString())
            if (extension.removeContainers) {
                if (extension.composeExecutor.version >= VersionNumber.parse('1.6.0')) {
                    String[] args = ['down']
                    switch (extension.removeImages) {
                        case RemoveImages.All:
                        case RemoveImages.Local:
                            args += ['--rmi', "${extension.removeImages}".toLowerCase()]
                            break
                        default:
                            break
                    }
                    if(extension.removeVolumes) {
                        args += ['--volumes']
                    }
                    if (extension.removeOrphans()) {
                        args += '--remove-orphans'
                    }
                    extension.composeExecutor.execute(args)
                } else {
                    extension.composeExecutor.execute('rm', '-f')
                }
            }
        }
    }
}
