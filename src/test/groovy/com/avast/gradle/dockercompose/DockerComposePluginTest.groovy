package com.avast.gradle.dockercompose

import com.avast.gradle.dockercompose.tasks.ComposeBuild
import com.avast.gradle.dockercompose.tasks.ComposeDown
import com.avast.gradle.dockercompose.tasks.ComposeDownForced
import com.avast.gradle.dockercompose.tasks.ComposeLogs
import com.avast.gradle.dockercompose.tasks.ComposePull
import com.avast.gradle.dockercompose.tasks.ComposePush
import com.avast.gradle.dockercompose.tasks.ComposeUp
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.IgnoreIf
import spock.lang.Specification

import static org.gradle.util.VersionNumber.parse

class DockerComposePluginTest extends Specification {
    def "add tasks and extension to the project"() {
        def project = ProjectBuilder.builder().build()
        when:
            project.plugins.apply 'docker-compose'
        then:
            project.tasks.composeUp instanceof ComposeUp
            project.tasks.composeDown instanceof ComposeDown
            project.tasks.composeDownForced instanceof ComposeDownForced
            project.tasks.composePull instanceof ComposePull
            project.tasks.composePush instanceof ComposePush
            project.tasks.composeBuild instanceof ComposeBuild
            project.tasks.composeLogs instanceof ComposeLogs
            project.extensions.findByName('dockerCompose') instanceof ComposeExtension
    }

    def "allows to define extra properties"() {
        def project = ProjectBuilder.builder().build()
        when:
        project.plugins.apply 'docker-compose'
        project.dockerCompose {
            ext.foo = "bar"
            ext {
                bar = "foo"
            }
            environment.put "FOO_SETTING", project.rootProject.name
        }
        then:
        project.dockerCompose.foo == "bar"
        project.dockerCompose.bar == "foo"
        project.dockerCompose.ext
    }

    def "add tasks of nested settings"() {
        def project = ProjectBuilder.builder().build()
        when:
        project.plugins.apply 'docker-compose'
        project.dockerCompose {
                nested {
                    useComposeFiles = ['test.yml']
                }
            }
        then:
        project.tasks.nestedComposeUp instanceof ComposeUp
        project.tasks.nestedComposeDown instanceof ComposeDown
        project.tasks.nestedComposeDownForced instanceof ComposeDownForced
        project.tasks.nestedComposePull instanceof ComposePull
        project.tasks.composePush instanceof ComposePush
        project.tasks.nestedComposeBuild instanceof ComposeBuild
        project.tasks.nestedComposeLogs instanceof ComposeLogs
        ComposeUp up = project.tasks.nestedComposeUp
        up.settings.useComposeFiles.get() == ['test.yml']
    }

    def "is possible to access servicesInfos of nested setting"() {
        def project = ProjectBuilder.builder().build()
        when:
        project.plugins.apply 'docker-compose'
        project.dockerCompose {
            nested {
                useComposeFiles = ['test.yml']
            }
        }
        then:
        project.dockerCompose.nested.servicesInfos instanceof Map<String, ServiceInfo>
    }

    def "is possible to override nested settings"() {
        def project = ProjectBuilder.builder().build()
        when:
        project.plugins.apply 'docker-compose'
        project.dockerCompose {
            removeVolumes = true
            nested {
                useComposeFiles = ['test.yml']
                removeVolumes = false
                ignorePullFailure = true
                ignorePushFailure = true
            }
        }
        then:
        project.dockerCompose.nested.removeVolumes.get() == false
        project.dockerCompose.removeVolumes.get() == true
        project.dockerCompose.ignorePullFailure.get() == false
        project.dockerCompose.ignorePushFailure.get() == false
        project.dockerCompose.nested.ignorePullFailure.get() == true
        project.dockerCompose.nested.ignorePushFailure.get() == true
    }

    def "isRequiredBy() adds dependencies"() {
        def project = ProjectBuilder.builder().build()
        project.plugins.apply 'docker-compose'
        Task task = project.tasks.create('integrationTest')
        when:
            project.dockerCompose.isRequiredBy(task)
        then:
            task.dependsOn.find { it instanceof TaskProvider && ((TaskProvider)it).get() == project.tasks.composeUp }
            task.getFinalizedBy().getDependencies(task).any { it == project.tasks.composeDown }
    }

    def "isRequiredBy() adds dependencies when using TaskProvider"() {
        def project = ProjectBuilder.builder().build()
        project.plugins.apply 'docker-compose'
        TaskProvider<Task> taskProvider = project.tasks.register('integrationTest')
        when:
        project.dockerCompose.isRequiredBy(taskProvider)
        Task task = taskProvider.get()
        then:
        task.dependsOn.find { it instanceof TaskProvider && ((TaskProvider)it).get() == project.tasks.composeUp }
        task.getFinalizedBy().getDependencies(task).any { it == project.tasks.composeDown }
    }

    def "isRequiredBy() adds dependencies when using TaskProvider and with dependent classes task"() {
        def project = ProjectBuilder.builder().build()
        project.plugins.apply 'docker-compose'
        TaskProvider<Task> classesTaskProvider = project.tasks.register('classes')
        TaskProvider<Task> taskProvider = project.tasks.register('integrationTest')
        taskProvider.configure { it.dependsOn classesTaskProvider }
        when:
        project.dockerCompose.isRequiredBy(taskProvider)
        Task task = taskProvider.get()
        then:
        task.dependsOn.find { it instanceof TaskProvider && ((TaskProvider)it).get() == project.tasks.composeUp }
        task.getFinalizedBy().getDependencies(task).any { it == project.tasks.composeDown }
    }

    def "isRequiredBy() adds dependencies for nested settings"() {
        def project = ProjectBuilder.builder().build()
        project.plugins.apply 'docker-compose'
        Task task = project.tasks.create('integrationTest')
        when:
        project.dockerCompose {
            nested {
                useComposeFiles = ['test.yml']
                isRequiredBy(task)
            }
        }
        then:
        task.dependsOn.find { it instanceof TaskProvider && ((TaskProvider)it).get() == project.tasks.nestedComposeUp }
        task.getFinalizedBy().getDependencies(task).any { it == project.tasks.nestedComposeDown }
    }

    def "add tasks of nested settings and isRequiredBy() adds dependencies for nested settings when using simplified syntax"() {
        def project = ProjectBuilder.builder().build()
        project.plugins.apply 'docker-compose'
        Task task = project.tasks.create('integrationTest')
        when:
        project.dockerCompose {
            isRequiredByIntegrationTest 'test.yml'
        }
        then:
        project.tasks.integrationTestComposeUp instanceof ComposeUp
        project.tasks.integrationTestComposeDown instanceof ComposeDown
        project.tasks.integrationTestComposeDownForced instanceof ComposeDownForced
        project.tasks.integrationTestComposePull instanceof ComposePull
        project.tasks.integrationTestComposePush instanceof ComposePush
        project.tasks.integrationTestComposeBuild instanceof ComposeBuild
        project.tasks.integrationTestComposeLogs instanceof ComposeLogs
        ComposeUp up = project.tasks.integrationTestComposeUp
        up.settings.useComposeFiles.get() == ['test.yml']
        task.dependsOn.find { it instanceof TaskProvider && ((TaskProvider)it).get() == project.tasks.integrationTestComposeUp }
        task.getFinalizedBy().getDependencies(task).any { it == project.tasks.integrationTestComposeDown }
    }

    def "isRequiredBy ensures right order of tasks"() {
        def project = ProjectBuilder.builder().build()
        project.plugins.apply 'docker-compose'
        project.plugins.apply 'java'
        when:
            project.dockerCompose.isRequiredBy(project.tasks.test)
        then:
            project.tasks.composeUp.shouldRunAfter.mutableValues.any { it == project.tasks.testClasses }
            noExceptionThrown()
    }

    def "allows to read servicesInfos from another task"() {
        def f = Fixture.withNginx()
        def integrationTestTask = f.project.tasks.create('integrationTest').doLast {
            ContainerInfo webInfo = f.project.dockerCompose.servicesInfos.web.firstContainer
            assert "http://${webInfo.host}:${webInfo.tcpPorts[80]}".toURL().text.contains('nginx')
            assert webInfo.ports == webInfo.tcpPorts
            assert !webInfo.containerHostname.isEmpty()
            assert webInfo.inspection.size() > 0
        }
        when:
            f.project.tasks.composeUp.up()
            integrationTestTask.actions.forEach { it.execute(integrationTestTask) }
        then:
            noExceptionThrown()
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
    }

    def "reconnect to previously executed up task"() {
        def f = Fixture.withNginx()
        when:
        f.project.dockerCompose.stopContainers = false
        def t = System.nanoTime()
        f.project.tasks.composeUp.up()
        def firstDuration = System.nanoTime() - t
        t = System.nanoTime()
        f.project.tasks.composeUp.up()
        def secondDuration = System.nanoTime() - t
        then:
        noExceptionThrown()
        secondDuration < firstDuration
        f.project.tasks.composeUp.wasReconnected == true
        cleanup:
        f.project.tasks.composeDownForced.down()
        f.close()
    }

    def "does not reconnect to previously executed up task if the container is killed"() {
        def f = Fixture.withNginx()
        when:
        f.project.dockerCompose.stopContainers = false
        f.project.tasks.composeUp.up()
        f.project.dockerCompose.dockerExecutor.execute('kill', f.project.dockerCompose.servicesInfos.values().find().firstContainer.containerId)
        f.project.tasks.composeUp.up()
        then:
        noExceptionThrown()
        f.project.tasks.composeUp.wasReconnected == false
        cleanup:
        f.project.tasks.composeDownForced.down()
        f.close()
    }

    def "allows pull"() {
        def f = Fixture.withNginx()
        when:
            f.project.dockerCompose.startedServices = ['web']
            f.project.tasks.composePull.pull()
        then:
            noExceptionThrown()
        cleanup:
            f.close()
    }

    def "exposes environment variables and system properties"() {
        def f = Fixture.custom(composeFileContent)
        f.project.plugins.apply 'java'
        f.project.tasks.composeUp.up()
        Test test = f.project.tasks.test as Test
        when:
            f.project.dockerCompose.exposeAsEnvironment(test)
            f.project.dockerCompose.exposeAsSystemProperties(test)
        then:
            test.environment.containsKey('WEB_HOST')
            test.environment.containsKey('WEB_CONTAINER_HOSTNAME')
            test.environment.containsKey('WEB_TCP_80')
            test.environment.containsKey('WEB_UDP_81')
            test.systemProperties.containsKey('web.host')
            test.systemProperties.containsKey('web.containerHostname')
            test.systemProperties.containsKey('web.tcp.80')
            test.systemProperties.containsKey('web.udp.81')
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
        where:
            // test it for both compose file version 1 and 2
            composeFileContent << ['''
            web:
                image: nginx:stable
                ports:
                  - 80
                  - 81/udp
        ''', '''
            version: '2'
            services:
                web:
                    image: nginx:stable
                    ports:
                      - 80
                      - 81/udp
        ''']
    }

    private static boolean isRunningOnWindows() { System.properties['os.name'].toString().toLowerCase().startsWith('windows') }
    private static boolean isRunningOnMac() { System.properties['os.name'].toString().toLowerCase().startsWith('macos') || System.properties['os.name'].toString().toLowerCase().startsWith('mac os') }

    @IgnoreIf({ DockerComposePluginTest.isRunningOnWindows() || DockerComposePluginTest.isRunningOnMac() })
    def "expose localhost as a host for container with HOST networking"() {
        def f = Fixture.custom('''
            version: '2'
            services:
                web:
                    image: nginx:stable
                    network_mode: host
        ''')
        f.project.plugins.apply 'java'
        f.extension.projectName = 'test'
        f.project.tasks.composeUp.up()
        Test test = f.project.tasks.test as Test
        when:
            f.project.dockerCompose.exposeAsEnvironment(test)
            f.project.dockerCompose.exposeAsSystemProperties(test)
        then:
            test.environment.get('WEB_HOST') == 'localhost'
            test.systemProperties.get('web.host') == 'localhost'
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
    }

    def "docker-compose substitutes environment variables"() {
        def f = Fixture.custom('''
            web:
                image: nginx:stable
                ports:
                  - $MY_WEB_PORT
        ''')
        def integrationTestTask = f.project.tasks.create('integrationTest').doLast {
            ContainerInfo webInfo = f.project.dockerCompose.servicesInfos.web.firstContainer
            assert webInfo.ports.containsKey(80)
        }
        when:
            f.extension.useComposeFiles = ['docker-compose.yml']
            f.extension.environment.put 'MY_WEB_PORT', 80
            f.extension.waitForTcpPorts = false  // checked in assert
            f.project.tasks.composeUp.up()
            integrationTestTask.actions.forEach { it.execute(integrationTestTask) }
        then:
            noExceptionThrown()
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
    }

    @IgnoreIf({ System.getenv('DOCKER_COMPOSE_VERSION') == null || parse(System.getenv('DOCKER_COMPOSE_VERSION')) >= parse('1.13.0') })
    def "exception is thrown for scale option if unsupported docker-compose is used"() {
        def f = Fixture.withNginx()
        f.extension.scale = ['web': 2]
        when:
            f.project.tasks.composeUp.up()
        then:
            thrown(UnsupportedOperationException)
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
    }

    @IgnoreIf({ System.getenv('DOCKER_COMPOSE_VERSION') != null && parse(System.getenv('DOCKER_COMPOSE_VERSION')) < parse('1.13.0') })
    def "docker-compose scale option launches multiple instances of service"() {
        def f = Fixture.withNginx()
        f.extension.scale = ['web': 2]
        def integrationTestTask = f.project.tasks.create('integrationTest').doLast {
            def webInfos = project.dockerCompose.servicesInfos.web.containerInfos
            assert webInfos.size() == 2
            assert webInfos.containsKey('web_1')
            assert webInfos.containsKey('web_2')
        }
        when:
            f.project.tasks.composeUp.up()
            integrationTestTask.actions.forEach { it.execute(integrationTestTask) }
        then:
            noExceptionThrown()
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
    }

    @IgnoreIf({ System.getenv('DOCKER_COMPOSE_VERSION') != null && parse(System.getenv('DOCKER_COMPOSE_VERSION')) < parse('1.13.0') })
    def "environment variables and system properties exposed for all scaled containers"() {
        def f = Fixture.withNginx()
        f.project.plugins.apply 'java'
        f.extension.scale = ['web': 2]
        f.project.tasks.composeUp.up()
        Test test = f.project.tasks.test as Test
        when:
            f.project.dockerCompose.exposeAsEnvironment(test)
            f.project.dockerCompose.exposeAsSystemProperties(test)
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
            f.project.tasks.composeDown.down()
            f.close()
    }

    @IgnoreIf({ System.getenv('DOCKER_COMPOSE_VERSION') != null && parse(System.getenv('DOCKER_COMPOSE_VERSION')) < parse('1.13.0') })
    def "docker-compose scale to 0 does not cause exceptions because of missing first container"() {
        def f = Fixture.custom('''
            web:
                image: nginx:stable
                ports:
                  - 80
            z:
                image: nginx:stable
                ports: []
        ''')
        f.extension.scale = ['web': 0]
        def integrationTestTask = f.project.tasks.create('integrationTest').doLast {
            def webInfos = project.dockerCompose.servicesInfos.web.containerInfos
            assert webInfos.size() == 0
        }
        when:
            f.project.tasks.composeUp.up()
            integrationTestTask.actions.forEach { it.execute(integrationTestTask) }
        then:
            noExceptionThrown()
        cleanup:
            f.project.tasks.composeDown.down()
            f.close()
    }

    def "exposes environment variables and system properties for container with custom name"() {
        def f = Fixture.custom(composeFileContent)
        f.project.plugins.apply 'java'
        f.project.tasks.composeUp.up()
        Test test = f.project.tasks.test as Test
        when:
        f.project.dockerCompose.exposeAsEnvironment(test)
        f.project.dockerCompose.exposeAsSystemProperties(test)
        then:
        test.environment.containsKey('CUSTOM_CONTAINER_NAME_HOST')
        test.environment.containsKey('CUSTOM_CONTAINER_NAME_CONTAINER_HOSTNAME')
        test.environment.containsKey('CUSTOM_CONTAINER_NAME_TCP_80')
        test.systemProperties.containsKey('custom_container_name.host')
        test.systemProperties.containsKey('custom_container_name.containerHostname')
        test.systemProperties.containsKey('custom_container_name.tcp.80')
        cleanup:
        f.project.tasks.composeDown.down()
        f.close()
        where:
        // test it for both compose file version 1 and 2
        composeFileContent << ['''
            web:
                container_name: custom_container_name
                image: nginx:stable
                ports:
                  - 80
        ''', '''
            version: '2'
            services:
                web:
                    container_name: custom_container_name
                    image: nginx:stable
                    ports:
                      - 80
        ''']
    }

    def "includeDependencies calculates dependencies correctly"() {
        def f = Fixture.custom(composeFileContent)
        f.project.plugins.apply 'java'
        f.project.dockerCompose.includeDependencies = true
        f.project.dockerCompose.startedServices = ['webMaster']
        f.project.plugins.apply 'docker-compose'
        f.project.tasks.composeUp.up()
        Test test = f.project.tasks.test as Test
        when:
        f.project.tasks.composeDown.down()
        then:
        def runningServices = f.project.dockerCompose.composeExecutor.execute('ps')
        !runningServices.contains("webMaster")
        !runningServices.contains("web0")
        !runningServices.contains("web1")

        cleanup:
        f.close()
        where:
        // test it for both compose file version 1 and 2
        composeFileContent << ['''
            web0:
                image: nginx:stable
                ports:
                  - 80
            web1:
                image: nginx:stable
                ports:
                  - 80
                links:
                  - web0
            webMaster:
                image: nginx:stable
                ports:
                  - 80
                links:
                  - web1
        ''', '''
            version: '2'
            services:
                web0:
                    image: nginx:stable
                    ports:
                      - 80
                web1:
                    image: nginx:stable
                    ports:
                      - 80
                    links:
                      - web0
                webMaster:
                    image: nginx:stable
                    ports:
                      - 80
                    links:
                      - web1
        ''']
    }

    def "works as expected for container with network from another container"() {
        def f = Fixture.custom(composeFileContent)
        f.project.plugins.apply 'java'
        f.project.plugins.apply 'docker-compose'
        when:
        f.project.tasks.composeUp.up()
        then:
        f.project.dockerCompose.servicesInfos.nginx.host == f.project.dockerCompose.servicesInfos.gw.host
        ServiceInfo gwServiceInfo = f.project.dockerCompose.servicesInfos.gw
        "http://${gwServiceInfo.host}:${gwServiceInfo.tcpPorts[80]}".toURL().text.contains('nginx')
        cleanup:
        f.project.tasks.composeDown.down()
        f.close()
        where:
        composeFileContent << ['''
            version: '2.0'
            services:
              gw:
                image: alpine:3.9.6
                entrypoint: /bin/sleep
                command: 1h
                ports:
                  - 80
              nginx:
                image: nginx:stable
                network_mode: service:gw
        ''']
    }
}
