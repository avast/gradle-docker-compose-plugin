package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeSettings
import org.gradle.api.tasks.TaskAction

class ComposeDown extends ComposeDownForced {
    ComposeSettings settings

    ComposeDown() {
        group = 'docker'
        description = 'Stops and removes containers of docker-compose project (only if stopContainers is set to true)'
    }

    @TaskAction
    void down() {
        if (settings.stopContainers) {
            super.down()
        }
    }
}
