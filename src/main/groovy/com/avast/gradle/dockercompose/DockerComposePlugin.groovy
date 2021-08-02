package com.avast.gradle.dockercompose

import org.gradle.api.Plugin
import org.gradle.api.Project

class DockerComposePlugin implements Plugin<Project> {
    @Override
    void apply(Project project) {
        // project parameter is no required for later Gradle version but we want to support also older Gradle versions
        project.extensions.create('dockerCompose', ComposeExtension, project)
    }
}
