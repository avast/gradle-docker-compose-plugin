package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExtension
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class ComposePull extends DefaultTask {

    ComposeExtension extension

    ComposePull() {
        group = 'docker'
        description = 'Pulls and builds all images of docker-compose project'
    }

    @TaskAction
    void pull() {
        if (extension.buildBeforeUp) {
            extension.composeExecutor.execute('build', *extension.startedServices)
        }
        extension.composeExecutor.execute('pull', *extension.startedServices)
    }
}
