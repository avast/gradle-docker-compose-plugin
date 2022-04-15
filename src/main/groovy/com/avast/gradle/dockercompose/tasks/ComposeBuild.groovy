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
abstract class ComposeBuild extends DefaultTask {

    @Internal
    abstract ListProperty<String> getBuildAdditionalArgs()

    @Internal
    abstract ListProperty<String> getStartedServices()

    @Internal
    abstract Property<ComposeExecutor> getComposeExecutor()

    ComposeBuild() {
        group = 'docker'
        description = 'Builds images for services of docker-compose project'
    }

    @TaskAction
    void build() {
        String[] args = ['build']
        args += (List<String>) buildAdditionalArgs.get()
        args += (List<String>) startedServices.get()
        composeExecutor.get().execute(args)
    }
}
