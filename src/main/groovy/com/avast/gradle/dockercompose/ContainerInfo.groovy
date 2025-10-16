package com.avast.gradle.dockercompose

import com.fasterxml.jackson.annotation.JsonIgnore
import groovy.transform.Immutable

@Immutable
class ContainerInfo {
    /* For example serviceName_1 */
    String instanceName
    ServiceHost serviceHost
    /* Mapping from exposed to forwarded port. */
    Map<Integer, Integer> tcpPorts
    Map<Integer, Integer> udpPorts
    /* Docker inspection */
    Map<String, Object> inspection
    @JsonIgnore String getContainerId() { inspection.Id }
    @JsonIgnore String getContainerHostname() { inspection.Config.Hostname }

    @JsonIgnore String getHost() { serviceHost.host }
    @JsonIgnore Map<Integer, Integer> getPorts() { tcpPorts }
    @JsonIgnore Integer getPort() { ports.values().find() }
    @JsonIgnore Integer getTcpPort() { tcpPorts.values().find() }
    @JsonIgnore Integer getUdpPort() { udpPorts.values().find() }


    @Override
    public String toString() {
        return "ContainerInfo{" +
                "instanceName='" + instanceName + '\'' +
                ", serviceHost=" + serviceHost +
                ", tcpPorts=" + tcpPorts +
                ", udpPorts=" + udpPorts +
                ", inspection=" + inspection +
                '}';
    }
}
