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

    def placeholder = '<CONFLUENT_KAFKA_VERSION>'
    def composeWithConfluentKafka = """
            version: '3.9'
            services:
                zookeeper:
                    image: zookeeper:3.5.9
                    hostname: zookeeper
                    environment:
                      ZOO_MY_ID: 1
                kafka:
                    image: confluentinc/cp-kafka:$placeholder
                    ports:
                        - "9092:9092"
                    environment:
                        KAFKA_BROKER_ID: 1
                        KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
                        KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: '1\'
                        KAFKA_AUTO_CREATE_TOPICS_ENABLE: 'true\'
                        KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INSIDE:PLAINTEXT,OUTSIDE:PLAINTEXT
                        KAFKA_LISTENERS: INSIDE://:9093,OUTSIDE://:9092
                        KAFKA_ADVERTISED_LISTENERS: INSIDE://kafka:9093,OUTSIDE://localhost:9092
                        KAFKA_INTER_BROKER_LISTENER_NAME: INSIDE
                        KAFKA_LOG4J_LOGGERS: "kafka.controller=WARN,kafka.producer.async.DefaultEventHandler=WARN,state.change.logger=WARN"
                    depends_on:
                        - zookeeper
            """

    @Unroll
    def "should start docker compose with project name: #projectName and Kafka: #confluentKafkaVersion"() {
        given:
        def withVersion = composeWithConfluentKafka.replace(placeholder, confluentKafkaVersion)
        def f = Fixture.custom(withVersion, projectName)
        f.project.plugins.apply 'java'
        f.project.plugins.apply 'docker-compose'

        when:
        f.project.tasks.composeUp.up()

        then:
        def configuredServices = f.project.dockerCompose.composeExecutor.getServiceNames()
        configuredServices.containsAll('zookeeper', 'kafka')

        cleanup:
        f.project.tasks.composeDownForced.down()
        f.close()

        where:
        projectName              | confluentKafkaVersion
        'lorem-ipsum-dolor'      | '5.4.6'
        'loremipsumdolorsitamet' | '5.4.6' // this version of Confluent Kafka fails with longer project name
        'loremipsumdolorsitamet' | '5.5.7'
        'loremipsumdolorsitamet' | '6.0.5'
        'loremipsumdolorsitamet' | '6.2.2'
        'loremipsumdolorsitamet' | '7.0.1'
    }

    @Unroll
    def "getServiceNames calculates service names correctly when includeDependencies is #includeDependencies"(){
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
        f.project.tasks.composeDownForced.down()
        f.close()

        where:
        retain << [true, false]
    }
}
