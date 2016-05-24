package com.avast.gradle.dockercompose

import groovy.transform.Immutable

@Immutable
class ServiceHost {
    String host
    ServiceHostType type
}

enum ServiceHostType {
    NetworkGateway,
    RemoteDockerHost,
    LocalHost
}
