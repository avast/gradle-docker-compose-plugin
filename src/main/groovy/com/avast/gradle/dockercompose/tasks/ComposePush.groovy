package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeSettings
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

class ComposePush extends DefaultTask {

    @Internal
    ComposeSettings settings

    ComposePush() {
        group = 'docker'
        description = 'Pushes images for services of docker-compose project'
    }

    @TaskAction
    void push() {
        String[] args = ['push']
        if (settings.ignorePushFailure) {
            args += '--ignore-push-failures'
        }
        args += settings.pushServices
        settings.composeExecutor.execute(args)
    }
}