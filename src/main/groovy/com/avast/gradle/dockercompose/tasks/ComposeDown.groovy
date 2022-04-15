package com.avast.gradle.dockercompose.tasks

import groovy.transform.CompileStatic
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

@CompileStatic
abstract class ComposeDown extends ComposeDownForced {
    @Internal
    abstract Property<Boolean> getStopContainers()

    ComposeDown() {
        group = 'docker'
        description = 'Stops and removes containers of docker-compose project (only if stopContainers is set to true)'
    }

    @TaskAction
    void down() {
        if (stopContainers.get()) {
            super.down()
        } else {
            logger.lifecycle('You\'re trying to stop the containers, but stopContainers is set to false. Please use composeDownForced task instead.')
        }
    }
}
