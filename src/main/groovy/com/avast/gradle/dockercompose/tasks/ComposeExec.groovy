package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeSettings
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

@CompileStatic
class ComposeExec extends DefaultTask {

    @Internal
    ComposeSettings settings

    private String service

    ComposeExec() {
        group = 'docker'
        description = 'Execute a command in a running container.'
    }

    @TaskAction
    void push() {
        String[] args = ['exec']
        if (settings.noTty.get()) {
            args += '--no-TTY'
        }
        args+=service
        settings.composeExecutor.execute(args)
    }

    @Option(option = "service", description = "Service name to execute command.")
    void setService(String service) {
        this.service = service
    }
}