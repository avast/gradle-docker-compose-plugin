package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeSettings
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class ComposeBuild extends DefaultTask {

    ComposeSettings settings

    ComposeBuild() {
        group = 'docker'
        description = 'Builds images for services of docker-compose project'
    }

    @TaskAction
    void build() {
        settings.composeExecutor.execute('build', *settings.startedServices)
    }
}
