package com.avast.gradle.dockercompose

import groovy.transform.Immutable

@Immutable
class ServiceHost {
    String host
    ServiceHostType type


    @Override
    public String toString() {
        return "ServiceHost{" +
                "host='" + host + '\'' +
                ", type=" + type +
                '}';
    }
}

enum ServiceHostType {
    NetworkGateway,
    RemoteDockerHost,
    LocalHost,
    Host,
    DirectContainerAccess
}
