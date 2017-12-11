package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeSettings
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class ComposePull extends DefaultTask {

    ComposeSettings settings

    ComposePull() {
        group = 'docker'
        description = 'Pulls and builds all images of docker-compose project'
    }

    @TaskAction
    void pull() {
        if (settings.buildBeforeUp) {
            settings.composeExecutor.execute('build', *settings.startedServices)
        }
        String[] args = ['pull']
        if (settings.ignorePullFailure) {
            args += '--ignore-pull-failures'
        }
        settings.composeExecutor.execute(args, *settings.startedServices)
    }
}
