# docker-compose-gradle-plugin
[![Build Status](https://travis-ci.org/avast/docker-compose-gradle-plugin.svg?branch=master)](https://travis-ci.org/avast/docker-compose-gradle-plugin) [![Download](https://api.bintray.com/packages/avast/maven/docker-compose-gradle-plugin/images/download.svg) ](https://bintray.com/avast/maven/docker-compose-gradle-plugin/_latestVersion)

Simplifies usage of [Docker Compose](https://www.docker.com/docker-compose) for local development and integration testing in [Gradle](https://gradle.org/) environment.

`composeUp` task starts the application and waits till all containers become [healthy](https://docs.docker.com/engine/reference/builder/#/healthcheck) and all exposed TCP ports are open (so till the application is ready). It reads assigned host and ports of particular containers and stores them into `dockerCompose.servicesInfos` property.

`composeDown` task stops the application and removes the containers.

`composePull` task pulls and optionally builds the images required by the application. This is useful, for example, with a CI platform that caches docker images to decrease build times.

## Why to use Docker Compose?
1. I want to be able to run my application on my computer, and it must work for my colleagues as well. Just execute `docker-compose up` and I'm done.
2. I want to be able to test my application on my computer - I don't wanna wait till my application is deployed into dev/testing environment and acceptance/end2end tests get executed. I want to execute these tests on my computer - it means execute `docker-compose up` before these tests.

## Why this plugin?
You could easily ensure that `docker-compose up` is called before your tests but there are few gotchas that this plugin solves:

1. If you execute `docker-compose up -d` (_detached_) then this command returns immediately and your application is probably not able to serve requests at this time. This plugin waits till all containers become [healthy](https://docs.docker.com/engine/reference/builder/#/healthcheck) and all exported TCP ports of all services are open.
  - If waiting for healthy state or open TCP ports timeouts (default is 15 minutes) then it prints log of related service. 
2. It's recommended not to assign fixed values of exposed ports in `docker-compose.yml` (i.e. `8888:80`) because it can cause ports collision on integration servers. If you don't assign a fixed value for exposed port (use just `80`) then the port is exposed as a random free port. This plugin reads assigned ports (and even IP addresses of containers) and stores them into `dockerCompose.servicesInfo` map.

# Usage
The plugin must be applied on project that contains `docker-compose.yml` file. It supposes that [Docker Engine](https://www.docker.com/docker-engine) and [Docker Compose](https://www.docker.com/docker-compose) are installed and available in `PATH`.

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

// Or use the new Gradle Portal plugins:
// plugins {
//  id 'com.avast.gradle.docker-compose' version "$versionHere"
// }

dockerCompose.isRequiredBy(test) // hooks 'dependsOn composeUp' and 'finalizedBy composeDown'

dockerCompose {
    // useComposeFiles = ['docker-compose.yml', 'docker-compose.prod.yml'] // like 'docker-compose -f <file>'
    // captureContainersOutput = true // prints output of all containers to Gradle output - very useful for debugging
    // captureContainersOutputToFile = '/path/to/logFile' // sends output of all containers to a log file
    // stopContainers = false // doesn't call `docker-compose down` - useful for debugging
    // removeContainers = false
    // removeImages = "None" // Other accepted values are: "All" and "Local"
    // removeOrphans = false // Removes containers for services not defined in the Compose file
    // removeVolumes = false
    // projectName = 'my-project' // allow to set custom docker-compose project name (defaults to directory name)
    // executable = '/path/to/docker-compose' // allow to set the path of the docker-compose executable (usefull if not present in PATH)
    // dockerExecutable = '/path/to/docker' // allow to set the path of the docker executable (usefull if not present in PATH)
    // dockerComposeWorkingDirectory = '/path/where/docker-compose/is/invoked/from'
    // dockerComposeStopTimeout = java.time.Duration.ofSeconds(20) // time before docker-compose sends SIGTERM to the running containers after the composeDown task has been started
    // environment.put 'BACKEND_ADDRESS', '192.168.1.100' // Pass environment variable to 'docker-compose' for substitution in compose file
    // scale = [${serviceName1}: 5, ${serviceName2}: 2] // Pass docker compose --scale option like 'docker-compose up --scale serviceName1=5 --scale serviceName2=2'
}

test.doFirst {
    // exposes "${serviceName}_HOST" and "${serviceName}_TCP_${exposedPort}" environment variables
    // for example exposes "WEB_HOST" and "WEB_TCP_80" environment variables for service named `web` with exposed port `80`
    // if service is scaled using scale option, environment variables will be exposed for each service instance like "WEB_1_HOST", "WEB_1_TCP_80", "WEB_2_HOST", "WEB_2_TCP_80" and so on
    dockerCompose.exposeAsEnvironment(test)
    // exposes "${serviceName}.host" and "${serviceName}.tcp.${exposedPort}" system properties
    // for example exposes "web.host" and "web.tcp.80" system properties for service named `web` with exposed port `80`
    // if service is scaled using scale option, environment variables will be exposed for each service instance like "web_1.host", "web_1.tcp.80", "web_2.host", "web_2.tcp.80" and so on
    dockerCompose.exposeAsSystemProperties(test)
    // get information about container of service `web` (declared in docker-compose.yml)
    def webInfo = dockerCompose.servicesInfos.web.firstInstance
    // in case scale option is used, dockerCompose.servicesInfos.containerInfos will contain information about all running containers of service. Particular container can be retreived either by iterating the values of containerInfos map (key is service instance name, for example 'web_1')
    def webInfo = dockerCompose.servicesInfos.web.'web_1'
    // pass host and exposed TCP port 80 as custom-named Java System properties
    systemProperty 'myweb.host', webInfo.host
    systemProperty 'myweb.port', webInfo.ports[80]    
}
```

# Tips
* You can call `dockerCompose.isRequiredBy(anyTask)` for any task, for example for your custom `integrationTest` task.
* If some Dockerfile needs an artifact generated by Gradle then you can declare this dependency in a standard way, like `composeUp.dependsOn project(':my-app').distTar`
* All properties in `dockerCompose` have meaningful default values so you don't have to touch it. If you are interested then you can look at [ComposeExtension.groovy](/src/main/groovy/com/avast/gradle/dockercompose/ComposeExtension.groovy) for reference.
* `dockerCompose.servicesInfos` contains information about running containers so you must access this property after `composeUp` task is finished. So `doFirst` of your test task is perfect place where to access it.
* Plugin honours a `docker-compose.override.yml` file, but only when no files are specified with `useComposeFiles` (conform command-line behavior).
* Check [ContainerInfo.groovy](/src/main/groovy/com/avast/gradle/dockercompose/ContainerInfo.groovy) to see what you can know about running containers.
* You can determine the Docker host in your Gradle build (i.e. `docker-machine start`) and set the 'DOCKER_HOST' environment variable for compose to use: `dockerCompose { environment.put 'DOCKER_HOST', '192.168.64.9' }`
