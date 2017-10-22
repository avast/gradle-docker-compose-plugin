package com.avast.gradle.dockercompose

import org.gradle.api.Plugin
import org.gradle.api.Project

class DockerComposePlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        project.extensions.create('dockerCompose', ComposeExtension, project)
    }
}
