package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExtension
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecSpec

class ComposePull extends DefaultTask {

    ComposeExtension extension

    ComposePull() {
        group = 'docker'
        description = 'Pulls and builds all images of docker-compose project'
    }

    @TaskAction
    void pull() {
        if (extension.buildBeforeUp) {
            project.exec { ExecSpec e ->
                extension.setExecSpecWorkingDirectory(e)
                e.environment = extension.environment
                e.commandLine extension.composeCommand('build')
            }
        }
        project.exec { ExecSpec e ->
            extension.setExecSpecWorkingDirectory(e)
            e.environment = extension.environment
            e.commandLine extension.composeCommand('pull')
        }
    }
}
