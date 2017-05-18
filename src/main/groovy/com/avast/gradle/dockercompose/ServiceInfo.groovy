package com.avast.gradle.dockercompose

class ServiceInfo {
    String name
    List<ServiceInstanceInfo> serviceInstanceInfos = []
    
    ServiceInstanceInfo getInstanceByName(String name) {
        serviceInstanceInfos.find { it.instanceName == name }
    }
}
