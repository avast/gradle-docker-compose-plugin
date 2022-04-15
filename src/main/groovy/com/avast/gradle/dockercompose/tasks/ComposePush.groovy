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
abstract class ComposePush extends DefaultTask {

    @Internal
    abstract Property<Boolean> getIgnorePushFailure()

    @Internal
    abstract ListProperty<String> getPushServices()

    @Internal
    abstract Property<ComposeExecutor> getComposeExecutor()

    ComposePush() {
        group = 'docker'
        description = 'Pushes images for services of docker-compose project'
    }

    @TaskAction
    void push() {
        String[] args = ['push']
        if (ignorePushFailure.get()) {
            args += '--ignore-push-failures'
        }
        args += (List<String>) pushServices.get()
        composeExecutor.get().execute(args)
    }
}