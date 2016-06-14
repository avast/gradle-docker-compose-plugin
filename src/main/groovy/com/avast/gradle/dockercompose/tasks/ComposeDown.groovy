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
            project.exec { ExecSpec e ->
                e.environment = extension.environment
                e.commandLine extension.composeCommand('stop')
            }
            if (extension.removeContainers) {
                if (getDockerComposeVersion() >= VersionNumber.parse('1.6.0')) {
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
                    project.exec { ExecSpec e ->
                        e.environment = extension.environment
                        e.commandLine extension.composeCommand(args)
                    }
                } else {
                    project.exec { ExecSpec e ->
                        e.environment = extension.environment
                        e.commandLine extension.composeCommand('rm', '-f')
                    }
                }
            }
        }
    }

    VersionNumber getDockerComposeVersion() {
        new ByteArrayOutputStream().withStream { os ->
            project.exec { ExecSpec e ->
                e.environment = extension.environment
                e.commandLine extension.composeCommand('--version')
                e.standardOutput = os
            }
            VersionNumber.parse(os.toString().trim().findAll(/(\d+\.){2}(\d+)/).head())
        }
    }
}
