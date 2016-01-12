package com.avast.gradle.dockercompose

import com.avast.gradle.dockercompose.tasks.ComposeDown
import com.avast.gradle.dockercompose.tasks.ComposeUp
import org.gradle.api.Task
import org.gradle.api.internal.file.TmpDirTemporaryFileProvider
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
        }
        project.tasks.composeUp.up()
        when:
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
}
