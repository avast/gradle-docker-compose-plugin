package com.avast.gradle.dockercompose

import com.avast.gradle.dockercompose.tasks.ComposeDown
import com.avast.gradle.dockercompose.tasks.ComposePull
import com.avast.gradle.dockercompose.tasks.ComposeUp
import org.gradle.api.Plugin
import org.gradle.api.Project

class DockerComposePlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        ComposeUp upTask = project.tasks.create('composeUp', ComposeUp)
        ComposePull pullTask = project.tasks.create('composePull', ComposePull)
        ComposeDown downTask = project.tasks.create('composeDown', ComposeDown)
        ComposeExtension extension = project.extensions.create('dockerCompose', ComposeExtension, project, upTask, downTask)
        upTask.settings = extension
        upTask.downTask = downTask
        downTask.settings = extension
		pullTask.settings = extension
    }
}
