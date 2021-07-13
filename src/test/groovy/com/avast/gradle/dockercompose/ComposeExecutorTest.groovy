package com.avast.gradle.dockercompose

import org.gradle.api.tasks.testing.Test
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class ComposeExecutorTest extends Specification {
    @Shared
    def composeV1_webMasterWithDeps =
            '''
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
        '''

    @Shared
    def composeV2_webMasterWithDeps =
            '''
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
            '''

    @Shared
    def composeWithFailingContainer = '''
            version: '3.9'
            services:
                fail:
                    image: nginx:stable
                    command: bash -c "echo not so stable && exit 1"
                double_fail:
                    image: hello-world
                    depends_on:
                        fail:
                            condition: service_completed_successfully
            '''

    @Unroll
    def "getServiceNames calculates service names correctly when includeDependencies is #includeDependencies" () {
        def f = Fixture.custom(composeFile)
        f.project.plugins.apply 'java'
        f.project.dockerCompose.includeDependencies = includeDependencies
        f.project.dockerCompose.startedServices = ['webMaster']
        f.project.plugins.apply 'docker-compose'
        Test test = f.project.tasks.test as Test

        when:
        def configuredServices = f.project.dockerCompose.composeExecutor.getServiceNames()

        then:
        configuredServices.containsAll(expectedServices)

        cleanup:
        f.close()

        where:
        // test it for both compose file version 1 and 2
        includeDependencies | expectedServices              | composeFile
        true                | ["webMaster", "web0", "web1"] | composeV1_webMasterWithDeps
        false               | ["webMaster"]                 | composeV1_webMasterWithDeps
        true                | ["webMaster", "web0", "web1"] | composeV2_webMasterWithDeps
        false               | ["webMaster"]                 | composeV2_webMasterWithDeps
    }

    def "If composeUp fails, containers should be deleted depending on retainContainersOnStartupFailure setting"() {
        setup:
        def f = Fixture.custom(composeWithFailingContainer)
        f.project.plugins.apply 'java'
        f.project.dockerCompose.startedServices = ['fail', 'double_fail']
        f.project.dockerCompose.retainContainersOnStartupFailure = retain
        f.project.dockerCompose
        f.project.plugins.apply 'docker-compose'

        when:
        f.project.tasks.composeUp.up()

        then:
        thrown(RuntimeException)
        assert f.project.dockerCompose.composeExecutor.getContainerIds('fail').size() == (retain ? 1 : 0)
        assert f.project.dockerCompose.composeExecutor.getContainerIds('double_fail').isEmpty()

        cleanup:
        f.close()

        where:
        retain << [true, false]
    }
}
