package com.avast.gradle.dockercompose

import com.avast.gradle.dockercompose.tasks.ComposeDown
import com.avast.gradle.dockercompose.tasks.ComposeUp
import org.gradle.api.Task
import org.gradle.api.internal.file.TmpDirTemporaryFileProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

class DockerComposePluginTest extends Specification {
    def "add tasks and extension to the project"() {
        def project = ProjectBuilder.builder().build()
        when:
        project.plugins.apply 'docker-compose'
        then:
        project.tasks.composeUp instanceof ComposeUp
        project.tasks.composeDown instanceof ComposeDown
        project.extensions.findByName('dockerCompose') instanceof ComposeExtension
    }


    def "dockerCompose.isRequiredBy() adds dependencies"() {
        def project = ProjectBuilder.builder().build()
        project.plugins.apply 'docker-compose'
        Task task = project.tasks.create('integrationTest')
        when:
        project.dockerCompose.isRequiredBy(task)
        then:
        task.dependsOn.contains(project.tasks.composeUp)
        task.getFinalizedBy().getDependencies(task).any { it == project.tasks.composeDown }
    }


    def "allows usage from integration test"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            web:
                image: nginx
                ports:
                  - 80
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        project.tasks.create('integrationTest').doLast {
            ServiceInfo webInfo = project.dockerCompose.servicesInfos.web
            assert "http://${webInfo.host}:${webInfo.tcpPorts[80]}".toURL().text.contains('nginx')
            assert webInfo.ports == webInfo.tcpPorts
            assert !webInfo.containerHostname.isEmpty()
            assert webInfo.inspection.size() > 0
        }
        when:
        project.tasks.composeUp.up()
        project.tasks.integrationTest.execute()
        then:
        noExceptionThrown()
        cleanup:
        project.tasks.composeDown.down()
        try {
            projectDir.delete()
        } catch(ignored) {
            projectDir.deleteOnExit()
        }
    }

    def "can specify compose files to use"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'original.yml') << '''
            web:
                image: nginx
                ports:
                  - 80
        '''
        new File(projectDir, 'override.yml') << '''
            web:
                ports:
                  - 8080
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        def extension = (ComposeExtension) project.extensions.findByName('dockerCompose')
        project.tasks.create('integrationTest').doLast {
            ServiceInfo webInfo = project.dockerCompose.servicesInfos.web
            assert webInfo.ports.containsKey(8080)
            assert webInfo.ports.containsKey(80)
        }
        when:
        extension.waitForTcpPorts = false // port 8080 is a fake
        extension.useComposeFiles = ['original.yml', 'override.yml']
        project.tasks.composeUp.up()
        project.tasks.integrationTest.execute()
        then:
        noExceptionThrown()
        cleanup:
        project.tasks.composeDown.down()
        try {
            projectDir.delete()
        } catch(ignored) {
            projectDir.deleteOnExit()
        }

    }

    def "docker-compose.override.yml file honoured when no files specified"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            web:
                image: nginx
        '''
        new File(projectDir, 'docker-compose.override.yml') << '''
            web:
                ports:
                  - 80
            devweb:
                image: nginx
                ports:
                  - 80
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        project.tasks.create('integrationTest').doLast {
            assert project.dockerCompose.servicesInfos.web.ports.containsKey(80)
            assert project.dockerCompose.servicesInfos.devweb.ports.containsKey(80)
        }
        when:
        project.tasks.composeUp.up()
        project.tasks.integrationTest.execute()
        then:
        noExceptionThrown()
        cleanup:
        project.tasks.composeDown.down()
        try {
            projectDir.delete()
        } catch(ignored) {
            projectDir.deleteOnExit()
        }
    }

    def "docker-compose.override.yml file ignored when files are specified"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            web:
                image: nginx
        '''
        new File(projectDir, 'docker-compose.override.yml') << '''
            web:
                ports:
                  - 80
            devweb:
                image: nginx
                ports:
                  - 80
        '''
        new File(projectDir, 'docker-compose.prod.yml') << '''
            web:
                ports:
                  - 8080
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        def extension = (ComposeExtension) project.extensions.findByName('dockerCompose')
        project.tasks.create('integrationTest').doLast {
            ServiceInfo webInfo = project.dockerCompose.servicesInfos.web
            assert webInfo.ports.containsKey(8080)
            assert !webInfo.ports.containsKey(80)
            assert !project.dockerCompose.servicesInfos.devweb
        }
        when:
        extension.waitForTcpPorts = false // port 8080 is a fake
        extension.useComposeFiles = ['docker-compose.yml', 'docker-compose.prod.yml']
        project.tasks.composeUp.up()
        project.tasks.integrationTest.execute()
        then:
        noExceptionThrown()
        cleanup:
        project.tasks.composeDown.down()
        try {
            projectDir.delete()
        } catch(ignored) {
            projectDir.deleteOnExit()
        }
    }

    def "exposes environment variables and system properties"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << composeFileContent
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'java'
        project.plugins.apply 'docker-compose'
        project.tasks.composeUp.up()
        Test test = project.tasks.test as Test
        when:
        project.dockerCompose.exposeAsEnvironment(test)
        project.dockerCompose.exposeAsSystemProperties(test)
        then:
        test.environment.containsKey('WEB_HOST')
        test.environment.containsKey('WEB_CONTAINER_HOSTNAME')
        test.environment.containsKey('WEB_TCP_80')
        test.systemProperties.containsKey('web.host')
        test.systemProperties.containsKey('web.containerHostname')
        test.systemProperties.containsKey('web.tcp.80')
        cleanup:
        project.tasks.composeDown.down()
        try {
            projectDir.delete()
        } catch(ignored) {
            projectDir.deleteOnExit()
        }
        where:
        // test it for both compose file version 1 and 2
        composeFileContent << ['''
            web:
                image: nginx
                ports:
                  - 80
        ''', '''
            version: '2'
            services:
                web:
                    image: nginx
                    ports:
                      - 80
        ''']
    }

    def "expose localhost as a host for container with HOST networking"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            version: '2'
            services:
                web:
                    image: nginx
                    network_mode: host
                    ports:
                      - 80
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'java'
        project.plugins.apply 'docker-compose'
        project.tasks.composeUp.up()
        Test test = project.tasks.test as Test
        when:
        project.dockerCompose.exposeAsEnvironment(test)
        project.dockerCompose.exposeAsSystemProperties(test)
        then:
        test.environment.get('WEB_HOST') == 'localhost'
        test.systemProperties.get('web.host') == 'localhost'
        cleanup:
        project.tasks.composeDown.down()
        try {
            projectDir.delete()
        } catch(ignored) {
            projectDir.deleteOnExit()
        }
    }

    def "reads logs of service"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            hello:
                image: hello-world
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        project.tasks.composeUp.up()
        when:
        String output = project.tasks.composeUp.getServiceLogs('hello')
        then:
        output.contains('Hello from Docker')
        cleanup:
        project.tasks.composeDown.down()
        try {
            projectDir.delete()
        } catch(ignored) {
            projectDir.deleteOnExit()
        }
    }
}
