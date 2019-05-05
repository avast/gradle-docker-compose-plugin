package com.avast.gradle.dockercompose

import org.gradle.api.tasks.testing.Test
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class ComposeExecutorTest extends Specification
{

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

    @Unroll
    def "getServiceNames calculates service names correctly when includeDependencies is #includeDependencies" ()
    {
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
}
