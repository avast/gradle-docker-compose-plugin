package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExecutor
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

@CompileStatic
abstract class ComposePull extends DefaultTask {

    @Internal
    abstract Property<Boolean> getIgnorePullFailure()

    @Internal
    abstract ListProperty<String> getPullAdditionalArgs()

    @Internal
    abstract ListProperty<String> getStartedServices()

    @Internal
    abstract Property<ComposeExecutor> getComposeExecutor()

    ComposePull() {
        group = 'docker'
        description = 'Builds and pulls images of docker-compose project'
    }

    @TaskAction
    void pull() {
        String[] args = ['pull']
        if (ignorePullFailure.get()) {
            args += '--ignore-pull-failures'
        }
        args += (List<String>) pullAdditionalArgs.get()
        args += (List<String>) startedServices.get()
        composeExecutor.get().execute(args)
    }
}
