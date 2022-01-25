package com.avast.gradle.dockercompose

import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class ComposeExecutorTest extends Specification {
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
                    depends_on:
                      - web0
                webMaster:
                    image: nginx:stable
                    ports:
                      - 80
                    depends_on:
                      - web1
            '''

    @Unroll
    def "getServiceNames calculates service names correctly when includeDependencies is #includeDependencies" () {
        def f = Fixture.custom(composeV2_webMasterWithDeps)
        f.project.plugins.apply 'java'
        f.project.dockerCompose.includeDependencies = includeDependencies
        f.project.dockerCompose.startedServices = ['webMaster']
        f.project.plugins.apply 'docker-compose'

        when:
        def configuredServices = f.project.dockerCompose.composeExecutor.getServiceNames()

        then:
        configuredServices.containsAll(expectedServices)

        cleanup:
        f.close()

        where:
        // test it for both compose file version 1 and 2
        includeDependencies | expectedServices
        true                | ["webMaster", "web0", "web1"]
        false               | ["webMaster"]
    }
}
