package com.avast.gradle.dockercompose

import org.gradle.api.Project
import org.gradle.util.ConfigureUtil

class ComposeExtension extends ComposeSettings {
    ComposeExtension(Project project) {
        super(project, '')
    }

    private HashMap<String, ComposeSettings> settings = [:]

    def methodMissing(String name, def args) {
        if (name.startsWith('isRequiredBy') && args[0] instanceof String) {
            def taskName = name.substring('isRequiredBy'.length())
            taskName = taskName[0].toLowerCase() + taskName.substring(1)
            ComposeSettings s = settings.computeIfAbsent(name, { createNested(taskName) })
            s.useComposeFiles = [args[0] as String]
            project.tasks.findAll { it.name.equalsIgnoreCase(taskName) }.forEach { s.isRequiredBy(it) }
            s
        } else {
            ComposeSettings s = settings.computeIfAbsent(name, { createNested(name) })
            ConfigureUtil.configure(args[0] as Closure, s)
            s
        }
    }
}
