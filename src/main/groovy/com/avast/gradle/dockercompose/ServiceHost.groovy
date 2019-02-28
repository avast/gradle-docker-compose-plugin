package com.avast.gradle.dockercompose

import groovy.transform.Immutable

// @Immutable annotation generates a code that uses ImmutableASTTransformation class from Groovy library.
// When compiling for Gradle 5+ (so Groovy 2.5+) then the generated code uses a new method that was added in Groovy 2.5.
// Therefore this method is not available in Groovy 2.4, so in Gradle 4.x.
// So we disabled the @Immutable annotations because we want to support also Gradle 4.x users.
// We can uncomment them if one of these conditions were met:
//  1. Groovy of the latest Gradle fixes this forward compatibility.
//  2. There is almost no users of Gradle 4.x.

//@Immutable
class ServiceHost {
    String host
    ServiceHostType type
}

enum ServiceHostType {
    NetworkGateway,
    RemoteDockerHost,
    LocalHost,
    Host,
    DirectContainerAccess
}
