package com.avast.gradle.dockercompose

import org.gradle.api.Project
import org.gradle.util.ConfigureUtil

import javax.inject.Inject

class ComposeExtension extends ComposeSettings {
    @Inject
    ComposeExtension(Project project) {
        super(project, '', '')
    }

    private HashMap<String, ComposeSettings> settings = [:]

    private ComposeSettings getOrCreateNested(String name) {
        settings.computeIfAbsent(name, { cloneAsNested(name) })
    }

    ComposeSettings createNested(String name) {
        getOrCreateNested(name)
    }

    ComposeSettings nested(String name) {
        getOrCreateNested(name)
    }

    def propertyMissing(String name) {
        def s = settings.get(name)
        if (s) {
            return s
        }
        throw new MissingPropertyException(name, getClass())
    }

    def methodMissing(String name, def args) {
        if (name == "ext") throw new MissingMethodException(name, getClass(), args)
        // If the method name is 'isRequiredByXXX' then the name of nested configuration will be XXX
        // and we will call isRequiredBy(XXX) for the newly created nested configuration.
        // The method must have one parameter that is path to the Docker Compose file.
        if (name.startsWith('isRequiredBy') && args.length == 1 && args[0]) {
            def taskName = name.substring('isRequiredBy'.length())
            if (taskName.empty) throw new RuntimeException('You called isRequiredBy method with an argument that is not of type Task')
            taskName = taskName[0].toLowerCase() + taskName.substring(1)
            ComposeSettings s = getOrCreateNested(taskName)
            s.useComposeFiles = [args[0].toString()]
            project.tasks.findAll { it.name.equalsIgnoreCase(taskName) }.forEach { s.isRequiredBy(it) }
            s
        } else if (args.length == 1 && args[0] instanceof Closure) {
            ComposeSettings s = getOrCreateNested(name)
            ConfigureUtil.configure(args[0] as Closure, s)
            s
        } else {
            getOrCreateNested(name)
        }
    }
}
