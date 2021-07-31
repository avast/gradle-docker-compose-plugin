package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeSettings
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

@CompileStatic
class ComposePull extends DefaultTask {

    @Internal
    ComposeSettings settings

    ComposePull() {
        group = 'docker'
        description = 'Builds and pulls images of docker-compose project'
    }

    @TaskAction
    void pull() {
        if (settings.buildBeforePull.get()) {
            settings.buildTask.get().build()
        }
        String[] args = ['pull']
        if (settings.ignorePullFailure.get()) {
            args += '--ignore-pull-failures'
        }
        args += (List<String>)settings.pullAdditionalArgs.get()
        args += (List<String>)settings.startedServices.get()
        settings.composeExecutor.execute(args)
    }
}
