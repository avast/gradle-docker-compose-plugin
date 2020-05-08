package com.avast.gradle.dockercompose

import groovy.transform.Immutable

@Immutable(knownImmutableClasses = [ContainerInfo])
class ServiceInfo {
    String name
    /* Key is instance name, for example service_1 */
    Map<String, ContainerInfo> containerInfos = [:]

    String getHost() { firstContainer?.serviceHost.host }
    Map<Integer, Integer> getPorts() { tcpPorts }
    Map<Integer, Integer> getTcpPorts() { firstContainer?.tcpPorts ?: [:] }
    Map<Integer, Integer> getUdpPorts() { firstContainer?.udpPorts ?: [:] }
    Integer getPort() { firstContainer?.port }
    Integer getTcpPort() { firstContainer?.tcpPort }
    Integer getUdpPort() { firstContainer?.udpPort }

    ContainerInfo getFirstContainer() {
        containerInfos.values()?.find()
    }

    def propertyMissing(String name) {
        return containerInfos[name]
    }
}
