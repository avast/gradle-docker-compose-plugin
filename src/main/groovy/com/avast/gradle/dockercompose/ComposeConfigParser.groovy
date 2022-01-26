package com.avast.gradle.dockercompose

import org.gradle.internal.impldep.com.google.common.annotations.VisibleForTesting
import org.yaml.snakeyaml.Yaml

/**
 * Reads information from the output of docker-compose config
 */
class ComposeConfigParser
{
    /**
     * Given the result of docker-compose config, parses through the output and builds a dependency graph between a service and the service's dependencies. The full graph is traversed, such that child dependencies are calculated
     * @param composeConfigOutput the output of docker-compose config
     * @return a map of a service's dependencies keyed by the service.
     */
    static Map<String, Set<String>> findServiceDependencies (String composeConfigOutput)
    {
        Map<String, Object> parsed = new Yaml().load(composeConfigOutput)
        // if there is 'version' on top-level then information about services is in 'services' sub-tree
        Map<String, Object> services = (parsed.services ? parsed.services : parsed)
        Map<String, Set<String>> declaredServiceDependencies = services.collectEntries { [(it.key): getDirectServiceDependencies(it.value)] }
        services.keySet().collectEntries { [(it): calculateDependenciesFromGraph(it, declaredServiceDependencies)] }
    }

    protected static Set<String> getDirectServiceDependencies(Map service) {
        List<String> dependencies = []
        if (service.depends_on)
        {
            def dependsOn = service.depends_on
            // just a list of services without properties
            if(dependsOn instanceof List)
            {
                dependencies.addAll(dependsOn)
            }
            // services that have properties
            if(dependsOn instanceof Map)
            {
                dependencies.addAll(dependsOn.keySet())
            }
        }
        // in version one, links established service names
        if (service.links)
        {
            dependencies.addAll(service.links)
        }
        dependencies.toSet()
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
