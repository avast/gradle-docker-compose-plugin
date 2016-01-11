package com.avast.gradle.dockercompose

import groovy.transform.Immutable

@Immutable
class DockerHost {
    String host
    boolean isRemote
}
