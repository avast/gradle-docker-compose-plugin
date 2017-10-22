package com.avast.gradle.dockercompose

import com.avast.gradle.dockercompose.tasks.ComposeDown
import com.avast.gradle.dockercompose.tasks.ComposeUp
import org.gradle.api.Project

class ComposeExtension extends ComposeSettings {
    ComposeExtension(Project project, ComposeUp upTask, ComposeDown downTask) {
        super(project, upTask, downTask)
    }
}
