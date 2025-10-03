package com.avast.gradle.dockercompose

import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

class CustomComposeFilesTest extends Specification {
    def "can specify compose files to use"() {
        def projectDir = File.createTempDir("gradle", "projectDir")
        new File(projectDir, 'original.yml') << '''
            services:
                web:
                    image: nginx:stable
                    ports:
                      - 80
        '''
        new File(projectDir, 'override.yml') << '''
            services:
                web:
                    ports:
                      - 8080
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        def extension = (ComposeExtension) project.extensions.findByName('dockerCompose')
        def integrationTestTask = project.tasks.create('integrationTest').doLast {
            ContainerInfo webInfo = project.dockerCompose.servicesInfos.web.firstContainer
            assert webInfo.ports.containsKey(8080)
            assert webInfo.ports.containsKey(80)
        }
        when:
        extension.waitForTcpPorts = false // port 8080 is a fake
        extension.useComposeFiles = ['original.yml', 'override.yml']
        project.tasks.composeUp.up()
        integrationTestTask.actions.forEach { it.execute(integrationTestTask) }
        then:
        noExceptionThrown()
        cleanup:
        project.tasks.composeDown.down()
        try {
            projectDir.delete()
        } catch (ignored) {
            projectDir.deleteOnExit()
        }

    }

    def "docker-compose.override.yml file honoured when no files specified"() {
        def projectDir = File.createTempDir("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            services:
                web:
                    image: nginx:stable
        '''
        new File(projectDir, 'docker-compose.override.yml') << '''
            services:
                web:
                    ports:
                      - 80
                devweb:
                    image: nginx:stable
                    ports:
                      - 80
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        def integrationTestTask = project.tasks.create('integrationTest').doLast {
            assert project.dockerCompose.servicesInfos.web.firstContainer.ports.containsKey(80)
            assert project.dockerCompose.servicesInfos.devweb.firstContainer.ports.containsKey(80)
        }
        when:
        project.tasks.composeUp.up()
        integrationTestTask.actions.forEach { it.execute(integrationTestTask) }
        then:
        noExceptionThrown()
        cleanup:
        project.tasks.composeDown.down()
        try {
            projectDir.delete()
        } catch (ignored) {
            projectDir.deleteOnExit()
        }
    }

    def "docker-compose.override.yml file ignored when files are specified"() {
        def projectDir = File.createTempDir("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            services:
                web:
                    image: nginx:stable
        '''
        new File(projectDir, 'docker-compose.override.yml') << '''
            services:
                web:
                    ports:
                      - 80
                devweb:
                    image: nginx:stable
                    ports:
                      - 80
        '''
        new File(projectDir, 'docker-compose.prod.yml') << '''
            services:
                web:
                    ports:
                      - 8080
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        def extension = (ComposeExtension) project.extensions.findByName('dockerCompose')
        def integrationTestTask = project.tasks.create('integrationTest').doLast {
            ContainerInfo webInfo = project.dockerCompose.servicesInfos.web.firstContainer
            assert webInfo.ports.containsKey(8080)
            assert !webInfo.ports.containsKey(80)
            assert !project.dockerCompose.servicesInfos.devweb
        }
        when:
        extension.waitForTcpPorts = false // port 8080 is a fake
        extension.useComposeFiles = ['docker-compose.yml', 'docker-compose.prod.yml']
        project.tasks.composeUp.up()
        integrationTestTask.actions.forEach { it.execute(integrationTestTask) }
        then:
        noExceptionThrown()
        cleanup:
        project.tasks.composeDown.down()
        try {
            projectDir.delete()
        } catch (ignored) {
            projectDir.deleteOnExit()
        }
    }
}
