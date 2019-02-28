package com.avast.gradle.dockercompose

import groovy.transform.Immutable

//@Immutable
class ContainerInfo {
    /* For example serviceName_1 */
    String instanceName
    ServiceHost serviceHost
    /* Mapping from exposed to forwarded port. */
    Map<Integer, Integer> tcpPorts
    Map<Integer, Integer> udpPorts
    /* Docker inspection */
    Map<String, Object> inspection
    String getContainerId() { inspection.Id }
    String getContainerHostname() { inspection.Config.Hostname }

    String getHost() { serviceHost.host }
    Map<Integer, Integer> getPorts() { tcpPorts }
    Integer getPort() { ports.values().find() }
    Integer getTcpPort() { tcpPorts.values().find() }
    Integer getUdpPort() { udpPorts.values().find() }
}
