package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExtension
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecSpec

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
                e.commandLine 'docker-compose', 'stop'
            }
            if (extension.removeContainers) {
                project.exec { ExecSpec e ->
                    e.commandLine 'docker-compose', 'rm', '-f'
                }
            }
        }
    }
}
