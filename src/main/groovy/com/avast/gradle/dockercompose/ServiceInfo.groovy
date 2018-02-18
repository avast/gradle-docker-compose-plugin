package com.avast.gradle.dockercompose

import groovy.transform.Immutable

@Immutable(knownImmutableClasses = [ContainerInfo])
class ServiceInfo {
    String name
    /* Key is instance name, for example service_1 */
    Map<String, ContainerInfo> containerInfos = [:]

    String getHost() { firstContainer.serviceHost.host }
    Map<Integer, Integer> getPorts() { firstContainer.tcpPorts }
    Integer getPort() { ports.values().first() }
    Integer getTcpPort() { firstContainer.tcpPorts.values().first() }
    
    ContainerInfo getFirstContainer() {
        containerInfos.values().first()
    }

    def propertyMissing(String name) {
        return containerInfos[name]
    }
}
