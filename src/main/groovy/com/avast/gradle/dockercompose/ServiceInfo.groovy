package com.avast.gradle.dockercompose

import com.fasterxml.jackson.annotation.JsonIgnore
import groovy.transform.Immutable

@Immutable(knownImmutableClasses = [ContainerInfo], copyWith = true)
class ServiceInfo {
    String name
    /* Key is instance name, for example service_1 */
    Map<String, ContainerInfo> containerInfos = [:]

    @JsonIgnore String getHost() { firstContainer?.serviceHost.host }
    @JsonIgnore Map<Integer, Integer> getPorts() { tcpPorts }
    @JsonIgnore Map<Integer, Integer> getTcpPorts() { firstContainer?.tcpPorts ?: [:] }
    @JsonIgnore Map<Integer, Integer> getUdpPorts() { firstContainer?.udpPorts ?: [:] }
    @JsonIgnore Integer getPort() { firstContainer?.port }
    @JsonIgnore Integer getTcpPort() { firstContainer?.tcpPort }
    @JsonIgnore Integer getUdpPort() { firstContainer?.udpPort }

    @JsonIgnore ContainerInfo getFirstContainer() {
        containerInfos.values()?.find()
    }

    def propertyMissing(String name) {
        return containerInfos[name]
    }
}
