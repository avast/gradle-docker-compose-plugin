package com.avast.gradle.dockercompose

import groovy.transform.Immutable

@Immutable
class ServiceInfo {
    String name
    DockerHost host
    /* Mapping from exposed to forwarded port. */
    Map<Integer, Integer> tcpPorts
}
