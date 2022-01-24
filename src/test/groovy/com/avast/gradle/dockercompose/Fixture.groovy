package com.avast.gradle.dockercompose

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

class Fixture implements AutoCloseable {
    private final File projectDir
    final Project project
    final ComposeExtension extension

    static withNginx() {
        new Fixture('''
            web:
                image: nginx:stable
                command: bash -c "sleep 5 && nginx -g 'daemon off;'"
                ports:
                  - 80
        ''')
    }

    static withHelloWorld() {
        new Fixture('''
            hello:
                image: hello-world
        ''')
    }

    static plain() {
        new Fixture()
    }

    static custom(String composeFileContent, projectName = "test") {
        new Fixture(composeFileContent, projectName)
    }

    private Fixture(String composeFileContent = null, String projectName = "test") {
        if (composeFileContent) {
            projectDir = File.createTempDir("gradle", "projectDir")
            new File(projectDir, 'docker-compose.yml') << composeFileContent
            project = ProjectBuilder.builder().withName(projectName).withProjectDir(projectDir).build()
        } else {
            project = ProjectBuilder.builder().build()
        }
        project.plugins.apply 'docker-compose'
        extension = (ComposeExtension)project.extensions.findByName('dockerCompose')
    }

    @Override
    void close() throws Exception {
        if (projectDir) {
            try {
                projectDir.delete()
            } catch (ignored) {
                projectDir.deleteOnExit()
            }
        }
    }
}
