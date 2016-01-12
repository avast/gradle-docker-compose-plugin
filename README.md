# docker-compose-gradle-plugin
Simplifies usage of [Docker Compose](https://www.docker.com/docker-compose) for integration testing in [Gradle](https://gradle.org/) environment. It starts the application before tests and stops the application after the tests are done. It also waits till all exported TCP ports are open (so till the application is ready).

The plugin must be applied on project that contains `docker-compose.yml` file. It supposses that [Docker Engine](https://www.docker.com/docker-engine) and [Docker Compose](https://www.docker.com/docker-compose) are installed. The plugin calls these tools simply via [Project.exec()](https://docs.gradle.org/current/dsl/org.gradle.api.Project.html#org.gradle.api.Project:exec(groovy.lang.Closure)) method.

# Usage
If you are using [Java Gradle plugin](https://docs.gradle.org/current/userguide/java_plugin.html) then you might hook Docker Compose to the standard `test` task.
```gradle
buildscript {
    repositories {
        jcenter()
    }
    dependencies {
        classpath "com.avast.gradle:docker-compose-gradle-plugin:$versionHere"
    }
}

apply plugin: 'java'
apply plugin: 'docker-compose'

dockerCompose.isRequiredBy(test)

test.doFirst {
    // read current host and port of service named 'web' (declared in docker-compose.yml)
    def webInfo = dockerCompose.servicesInfos.web
    // pass current values to tests via Java System property
    systemProperty 'web.host', webInfo.host
    systemProperty 'web.port', webInfo.tcpPorts[80]
}
```

# Generic example
```gradle
buildscript {
    repositories {
        jcenter()
    }
    dependencies {
        classpath "com.avast.gradle:docker-compose-gradle-plugin:$versionHere"
    }
}
apply plugin: 'docker-compose'

dockerCompose {
    stopContainers = false // default is true
}

task myIntegrationTest << {
    // 'web' is name of service declared in docker-compose
    def webInfo = dockerCompose.servicesInfos.web
    // ServiceInfo has 'host' and `tcpPorts` properties; `tcpPorts` maps from exposed to forwarded port
    assert "http://${webInfo.host}:${webInfo.tcpPorts[80]}".toURL().text
}

dockerCompose.isRequiredBy(myIntegrationTest) // hooks dependsOn and finalizedBy to composeUp and composeDown
```

# Tips 
All properties in `dockerCompose` have meaningfull default values so you don't have to touch it. If you are interested then you can look at [ComposeExtension.groovy](/src/main/groovy/com/avast/gradle/dockercompose/ComposeExtension.groovy) for reference.

Also please note that `dockerCompose.servicesInfos` contains information about running containers so you must access this property immediatelly before you want to use it. So `doFirst` of your test task is perfect place where to access it.

It's recommended not to assign fixed forwarded ports in `docker-compose.yml` for automated build because it can cause ports collision on integration server.
