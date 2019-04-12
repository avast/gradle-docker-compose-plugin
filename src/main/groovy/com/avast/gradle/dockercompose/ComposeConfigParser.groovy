package com.avast.gradle.dockercompose

import org.gradle.internal.impldep.com.google.common.annotations.VisibleForTesting
import org.yaml.snakeyaml.Yaml

/**
 * Reads information from the output of docker-compose config
 */
class ComposeConfigParser
{
    /**
     * Given the result of docker-compose config, parses through the output and builds a dependency graph between a service and the service's dependencies. The full graph is travers, such that children dependencies are calculated
     * @param composeConfigOutput the output of docker-compose config
     * @return a map of a service's dependencies keyed by the service.
     */
    static Map<String, Set<String>> findServiceDependencies (String composeConfigOutput)
    {
        Yaml parser = new Yaml()
        def result = parser.load(composeConfigOutput)

        // if there is 'version' on top-level then information about services is in 'services' sub-tree
        def serviceList = result.version ? result.services : result

        def declaredServiceDependencies = [:]
        def services = serviceList.entrySet()

        services.each { entry ->
            def serviceName = entry.getKey()
            def service = entry.getValue()

            def dependencies = []
            if (service.depends_on)
            {
                dependencies.addAll(service.depends_on)
            }
            // in version one, links established service names
            if (service.links)
            {
                dependencies.addAll(service.links)
            }

            declaredServiceDependencies.put(serviceName, dependencies)
        }

        // compute the graph for each service
        def serviceDependencySet = [:]
        services.each { entry ->
            def service = entry.getKey()
            serviceDependencySet.put(service, calculateDependenciesFromGraph(service, declaredServiceDependencies))
        }

        return serviceDependencySet
    }

    /**
     * Given a map of a service's declared dependencies, calculates the full dependency set for a given service.
     * @param declaredDependencies a map of service's dependencies
     * @return a set of the service's full dependencies
     */
    @VisibleForTesting
    protected  static Set<String> calculateDependenciesFromGraph(String serviceName, Map<String, Set<String>> declaredDependencies) {

        def toVisit = []
        toVisit.add(serviceName)

        Set<String> serviceDependencies = []

        while(!toVisit.isEmpty()) {
            String visitedService = toVisit.removeAt(0)
            def dependents = declaredDependencies.get(visitedService)
            if(dependents) {
                toVisit.addAll(dependents)
                serviceDependencies.addAll(dependents)
            }
        }

        serviceDependencies
    }
}
