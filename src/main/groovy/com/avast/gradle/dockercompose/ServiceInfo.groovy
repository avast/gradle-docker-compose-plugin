package com.avast.gradle.dockercompose

import groovy.transform.Immutable

@Immutable
class ServiceInfo {
    String name
    List<ServiceInstanceInfo> serviceInstanceInfos = []

    String getHost() { serviceInstanceInfos[0].serviceHost.host }
    Map<Integer, Integer> getPorts() { serviceInstanceInfos[0].tcpPorts }
    Integer getPort() { ports.values().first() }
    Integer getTcpPort() { serviceInstanceInfos[0].tcpPorts.values().first() }
    
    ServiceInstanceInfo getInstanceByName(String name) {
        serviceInstanceInfos.find { it.instanceName == name }
    }
}
