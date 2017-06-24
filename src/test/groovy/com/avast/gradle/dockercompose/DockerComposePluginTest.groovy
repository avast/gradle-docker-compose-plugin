package com.avast.gradle.dockercompose

import com.avast.gradle.dockercompose.tasks.ComposeDown
import com.avast.gradle.dockercompose.tasks.ComposeUp
import com.avast.gradle.dockercompose.tasks.ComposePull
import org.gradle.api.Task
import org.gradle.api.internal.file.TmpDirTemporaryFileProvider
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.testing.Test
import org.gradle.internal.logging.events.LogEvent
import org.gradle.internal.logging.events.OutputEvent
import org.gradle.internal.logging.events.OutputEventListener
import org.gradle.internal.logging.slf4j.Slf4jLoggingConfigurer
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.IgnoreIf
import spock.lang.Specification

import static org.gradle.util.VersionNumber.parse

class DockerComposePluginTest extends Specification {
    String projectName = 'test'

    def "add tasks and extension to the project"() {
        def project = ProjectBuilder.builder().build()
        when:
            project.plugins.apply 'docker-compose'
        then:
            project.tasks.composeUp instanceof ComposeUp
            project.tasks.composeDown instanceof ComposeDown
            project.tasks.composePull instanceof ComposePull
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

    def "isRequiredBy ensures right order of tasks"() {
        def project = ProjectBuilder.builder().build()
        project.plugins.apply 'docker-compose'
        project.plugins.apply 'java'
        when:
            project.dockerCompose.isRequiredBy(project.tasks.test)
        then:
            project.tasks.composeUp.shouldRunAfter.values.any { it == project.tasks.testClasses }
            noExceptionThrown()
    }

    def "allows usage from integration test"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            web:
                image: nginx
                command: bash -c "sleep 5 && nginx -g 'daemon off;'"
                ports:
                  - 80
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        project.tasks.create('integrationTest').doLast {
            ContainerInfo webInfo = project.dockerCompose.servicesInfos.web.firstContainer
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
            } catch (ignored) {
                projectDir.deleteOnExit()
            }
    }

    def "allows pull from integration test"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            web:
                image: nginx
                command: bash -c "sleep 5 && nginx -g 'daemon off;'"
                ports:
                  - 80
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        when:
            project.tasks.composePull.pull()
        then:
            noExceptionThrown()
        cleanup:
            try {
                projectDir.delete()
            } catch (ignored) {
                projectDir.deleteOnExit()
            }
    }

    def "captures container output to stdout"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            web:
                image: nginx
                command: bash -c "echo 'heres some output' && sleep 5 && nginx -g 'daemon off;'"
                ports:
                  - 80
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()

        project.plugins.apply 'docker-compose'
        def extension = (ComposeExtension) project.extensions.findByName('dockerCompose')

        def stdout = new StringBuffer()

        new Slf4jLoggingConfigurer(new OutputEventListener() {
            @Override
            void onOutput(OutputEvent outputEvent) {
                if (outputEvent instanceof LogEvent) {
                    stdout.append(((LogEvent) outputEvent).message)
                }
            }
        }).configure(LogLevel.LIFECYCLE)

        when:
            extension.captureContainersOutput = true
            project.tasks.composeUp.up()
        then:
            noExceptionThrown()
            stdout.toString().contains("heres some output")
        cleanup:
            project.tasks.composeDown.down()
            try {
                projectDir.delete()
            } catch (ignored) {
                projectDir.deleteOnExit()
            }
    }

    def "captures container output to file"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            web:
                image: nginx
                command: bash -c "echo 'heres some output' && sleep 5 && nginx -g 'daemon off;'"
                ports:
                  - 80
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        def logFile = new File(projectDir, "web.log")

        project.plugins.apply 'docker-compose'
        def extension = (ComposeExtension) project.extensions.findByName('dockerCompose')

        when:
            extension.captureContainersOutput = "${logFile.absolutePath}"
            project.tasks.composeUp.up()
        then:
            noExceptionThrown()
            logFile.text.contains("heres some output")
        cleanup:
            project.tasks.composeDown.down()
            try {
                projectDir.delete()
            } catch (ignored) {
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
            ContainerInfo webInfo = project.dockerCompose.servicesInfos.web.firstContainer
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
            } catch (ignored) {
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
            assert project.dockerCompose.servicesInfos.web.firstContainer.ports.containsKey(80)
            assert project.dockerCompose.servicesInfos.devweb.firstContainer.ports.containsKey(80)
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
            } catch (ignored) {
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
            ContainerInfo webInfo = project.dockerCompose.servicesInfos.web.firstContainer
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
            } catch (ignored) {
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
            } catch (ignored) {
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

    @IgnoreIf({ System.properties['os.name'].toString().toLowerCase().startsWith('windows') || System.properties['os.name'].toString().toLowerCase().startsWith('macos') })
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
        def extension = (ComposeExtension) project.extensions.findByName('dockerCompose')
        extension.projectName = projectName
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
            } catch (ignored) {
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
        String containerId = project.dockerCompose.servicesInfos.hello.firstContainer.containerId
        when:
            String output = project.tasks.composeUp.getServiceLogs(containerId)
        then:
            output.contains('Hello from Docker')
        cleanup:
            project.tasks.composeDown.down()
            try {
                projectDir.delete()
            } catch (ignored) {
                projectDir.deleteOnExit()
            }
    }

    def "docker-compose substitutes environment variables"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            web:
                image: nginx
                ports:
                  - $MY_WEB_PORT
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        def extension = (ComposeExtension) project.extensions.findByName('dockerCompose')
        project.tasks.create('integrationTest').doLast {
            ContainerInfo webInfo = project.dockerCompose.servicesInfos.web.firstContainer
            assert webInfo.ports.containsKey(80)
        }
        when:
            extension.useComposeFiles = ['docker-compose.yml']
            extension.environment.put 'MY_WEB_PORT', 80
            extension.waitForTcpPorts = false  // checked in assert
            project.tasks.composeUp.up()
            project.tasks.integrationTest.execute()
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

    @IgnoreIf({ parse(System.getenv('DOCKER_COMPOSE_VERSION')) >= parse('1.13.0') })
    def "exception is thrown for scale option if unsupported docker-compose is used"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            web:
                image: nginx
                ports:
                  - 80
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        def extension = (ComposeExtension) project.extensions.findByName('dockerCompose')
        extension.scale = ['web': 2]
        when:
            project.tasks.composeUp.up()
        then:
            thrown(UnsupportedOperationException)
        cleanup:
            project.tasks.composeDown.down()
            try {
                projectDir.delete()
            } catch (ignored) {
                projectDir.deleteOnExit()
            }
    }

    @IgnoreIf({ parse(System.getenv('DOCKER_COMPOSE_VERSION')) < parse('1.13.0') })
    def "docker-compose scale option launches multiple instances of service"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            web:
                image: nginx
                ports:
                  - 80
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'docker-compose'
        def extension = (ComposeExtension) project.extensions.findByName('dockerCompose')
        extension.scale = ['web': 2]
        project.tasks.create('integrationTest').doLast {
            def webInfos = project.dockerCompose.servicesInfos.web.containerInfos
            assert webInfos.size() == 2
            assert webInfos.containsKey('web_1')
            assert webInfos.containsKey('web_2')
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
            } catch (ignored) {
                projectDir.deleteOnExit()
            }
    }

    @IgnoreIf({ parse(System.getenv('DOCKER_COMPOSE_VERSION')) < parse('1.13.0') })
    def "environment variables and system properties exposed for all scaled containers"() {
        def projectDir = new TmpDirTemporaryFileProvider().createTemporaryDirectory("gradle", "projectDir")
        new File(projectDir, 'docker-compose.yml') << '''
            web:
                image: nginx
                ports:
                  - 80
        '''
        def project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.plugins.apply 'java'
        project.plugins.apply 'docker-compose'
        def extension = (ComposeExtension) project.extensions.findByName('dockerCompose')
        extension.scale = ['web': 2]
        project.tasks.composeUp.up()
        Test test = project.tasks.test as Test
        when:
            project.dockerCompose.exposeAsEnvironment(test)
            project.dockerCompose.exposeAsSystemProperties(test)
        then:
            [1, 2].each { containerInstance ->
                assert test.environment.containsKey("WEB_${containerInstance}_HOST".toString())
                assert test.environment.containsKey("WEB_${containerInstance}_CONTAINER_HOSTNAME".toString())
                assert test.environment.containsKey("WEB_${containerInstance}_TCP_80".toString())
                assert test.systemProperties.containsKey("web_${containerInstance}.host".toString())
                assert test.systemProperties.containsKey("web_${containerInstance}.containerHostname".toString())
                assert test.systemProperties.containsKey("web_${containerInstance}.tcp.80".toString())
            }
        cleanup:
            project.tasks.composeDown.down()
            try {
                projectDir.delete()
            } catch (ignored) {
                projectDir.deleteOnExit()
            }
    }

    def "exposes environment variables and system properties for container with custom name"() {
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
        test.environment.containsKey('CUSTOM_CONTAINER_NAME_HOST')
        test.environment.containsKey('CUSTOM_CONTAINER_NAME_CONTAINER_HOSTNAME')
        test.environment.containsKey('CUSTOM_CONTAINER_NAME_TCP_80')
        test.systemProperties.containsKey('custom_container_name.host')
        test.systemProperties.containsKey('custom_container_name.containerHostname')
        test.systemProperties.containsKey('custom_container_name.tcp.80')
        cleanup:
        project.tasks.composeDown.down()
        try {
            projectDir.delete()
        } catch (ignored) {
            projectDir.deleteOnExit()
        }
        where:
        // test it for both compose file version 1 and 2
        composeFileContent << ['''
            web:
                container_name: custom_container_name
                image: nginx
                ports:
                  - 80
        ''', '''
            version: '2'
            services:
                web:
                    container_name: custom_container_name
                    image: nginx
                    ports:
                      - 80
        ''']
    }
}
