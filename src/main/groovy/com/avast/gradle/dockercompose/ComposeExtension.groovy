package com.avast.gradle.dockercompose

import org.gradle.api.Project
import org.gradle.util.ConfigureUtil

class ComposeExtension extends ComposeSettings {
    ComposeExtension(Project project) {
        super(project, '')
    }

    private HashMap<String, ComposeSettings> settings = [:]

    def methodMissing(String name, def args) {
        ComposeSettings s = settings.computeIfAbsent(name, { createNested(name) })
        ConfigureUtil.configure(args[0] as Closure, s)
        s
    }
}
