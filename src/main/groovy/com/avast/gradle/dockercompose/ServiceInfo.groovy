package com.avast.gradle.dockercompose

import groovy.transform.Immutable

@Immutable
class ServiceInfo {
    String name
    Map<String, ServiceInstanceInfo> serviceInstanceInfos = [:]

    String getHost() { firstInstance.serviceHost.host }
    Map<Integer, Integer> getPorts() { firstInstance.tcpPorts }
    Integer getPort() { ports.values().first() }
    Integer getTcpPort() { firstInstance.tcpPorts.values().first() }
    
    ServiceInstanceInfo getFirstInstance() {
        serviceInstanceInfos.values().first()
    }
    
    ServiceInstanceInfo getInstanceByName(String name) {
        serviceInstanceInfos.get(name)
    }
}
