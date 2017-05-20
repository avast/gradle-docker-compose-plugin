package com.avast.gradle.dockercompose

import groovy.transform.Immutable

@Immutable
class ServiceInfo {
    String name
    Map<String, ServiceInstanceInfo> serviceInstanceInfos = [:]

    String getHost() { serviceInstanceInfos.values().first().serviceHost.host }
    Map<Integer, Integer> getPorts() { serviceInstanceInfos.values().first().tcpPorts }
    Integer getPort() { ports.values().first() }
    Integer getTcpPort() { serviceInstanceInfos.values().first().tcpPorts.values().first() }
    
    ServiceInstanceInfo getFirstInstance() {
        serviceInstanceInfos.values().first()
    }
    
    ServiceInstanceInfo getInstanceByName(String name) {
        serviceInstanceInfos.get(name)
    }
}
