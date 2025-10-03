package com.avast.gradle.dockercompose

import spock.lang.Specification
import spock.lang.Unroll

class ComposeConfigParserTest extends Specification
{
    def "findServiceDependencies with a service two direct dependencies in version 3" ()
    {
        given: "compose config output for a service"
        def configOutput = """
            services:
              master:
                depends_on:
                  slave0:
                    condition: service_healthy
                  slave1:
                    condition: service_healthy
              slave0:
                expose:
                  - '22'
              slave1:
                expose:
                  - '23'
        """

        when: "findServiceDependencies is called"
        def dependenciesMap = ComposeConfigParser.findServiceDependencies(configOutput)

        then: "master has two dependencies"
        dependenciesMap["master"] == ["slave0", "slave1"] as Set

        and: "slave0 has no dependencies"
        dependenciesMap["slave0"].isEmpty()

        and: "slave1 has no dependencies"
        dependenciesMap["slave1"].isEmpty()
    }

    def "findServiceDependencies with a service two direct dependencies in version 1" ()
    {

        given: "compose config output for a service"
        def configOutput = """
        master:
          links:
            - slave0
            - slave1
        slave0:
            expose:
              - '22'
        slave1:
            expose:
              - '23'
        """

        when: "findServiceDependencies is called"
        def dependenciesMap = ComposeConfigParser.findServiceDependencies(configOutput)

        then: "master has two dependencies"
        dependenciesMap["master"] == ["slave0", "slave1"] as Set

        and: "slave0 has no dependencies"
        dependenciesMap["slave0"].isEmpty()

        and: "slave1 has no dependencies"
        dependenciesMap["slave1"].isEmpty()
    }

    def "findServiceDependencies with a service 4 indirect dependencies in version 3" ()
    {
        given: "compose config output for a service"
        def configOutput = """
            services:
              db:
                expose:
                  - 1414
              splunkForward:
                expose:
                  - 8444
              dataService:
                depends_on:
                  - db
                expose:
                  - '8080'
              audit:
                depends_on:
                  splunkForward:
                    condition: service_healthy
              ui:
                depends_on:
                  dataService:
                    condition: service_healthy
                  audit:
                    condition: service_healthy
                expose:
                  - '23'
        """

        when: "findServiceDependencies is called"
        def dependenciesMap = ComposeConfigParser.findServiceDependencies(configOutput)

        then: "ui has 4 dependencies (audit, splunkForward, dataService, db)"
        dependenciesMap["ui"] == ["audit", "splunkForward", "dataService", "db"] as Set

        and: "deals with list dependencies"
        dependenciesMap["dataService"] == ["db"] as Set
    }

    def "findServiceDependencies with a service 4 indirect dependencies in version 1" ()
    {
        given: "compose config output for a service"
        def configOutput = """
              db:
                expose:
                  - 1414
              splunkForward:
                expose:
                  - 8444
              dataService:
                links:
                  - db
                expose:
                  - '8080'
              audit:
                links:
                  - splunkForward
              ui:
                links:
                  - dataService
                  - audit
                expose:
                  - '23'
        """

        when: "findServiceDependencies is called"
        def dependenciesMap = ComposeConfigParser.findServiceDependencies(configOutput)

        then: "ui has 4 dependencies (audit, splunkForward, dataService, db)"
        dependenciesMap["ui"] == ["audit", "splunkForward", "dataService", "db"] as Set
    }

    @Unroll
    def "calculateDependenciesFromGraph computes dependencies for #service" ()
    {
        given: "a dependency graph"
        /**
         * services:
         *   a:
         *   b:
         *     depends_on:
         *       - a
         *   c:
         *     depends_on:
         *       - b
         *   d:
         *   e:
         *     depends_on:
         *       - c
         *       - d
         */
        def dependencyGraph = [
                    "a":[],
                    "b": ["a"],
                    "c": ["b"],
                    "d": [],
                    "e": ["c", "d"]
        ]

        when: "calculateDependenciesFromGraph is called for #service"
        def dependencies = ComposeConfigParser.calculateDependenciesFromGraph(service, dependencyGraph)

        then: "the service's dependency set is calculated correctly"
        dependencies == expectedSet

        where:
        service | expectedSet
        "a" | [] as Set
        "b" | ["a"] as Set
        "c" | ["a", "b"] as Set
        "d" | [] as Set
        "e" | ["a", "b", "c", "d"] as Set
    }
}
