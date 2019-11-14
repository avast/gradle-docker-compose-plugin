package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeSettings
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

@CompileStatic
class ComposePull extends DefaultTask {

    ComposeSettings settings

    ComposePull() {
        group = 'docker'
        description = 'Builds and pulls images of docker-compose project'
    }

    @TaskAction
    void pull() {
        if (settings.buildBeforePull) {
            settings.buildTask.get().build()
        }
        String[] args = ['pull']
        if (settings.ignorePullFailure) {
            args += '--ignore-pull-failures'
        }
        args += settings.pullAdditionalArgs
        args += settings.startedServices
        settings.composeExecutor.execute(args)
    }
}
