package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExtension
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
                e.commandLine extension.composeCommand('stop')
            }
            if (extension.removeContainers) {
                if (getDockerComposeVersion() >= VersionNumber.parse('1.6.0')) {
                    project.exec { ExecSpec e ->
                        e.commandLine extension.composeCommand('down', '--rmi', 'all', '--volumes')
                    }
                } else {
                    project.exec { ExecSpec e ->
                        e.commandLine extension.composeCommand('rm', '-f')
                    }
                }
            }
        }
    }

    VersionNumber getDockerComposeVersion() {
        new ByteArrayOutputStream().withStream { os ->
            project.exec { ExecSpec e ->
                e.commandLine extension.composeCommand('--version')
                e.standardOutput = os
            }
            VersionNumber.parse(os.toString().trim().findAll(/(\d+\.){2}(\d+)/).head())
        }
    }
}
