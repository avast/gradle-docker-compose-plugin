# gradle-docker-compose-plugin [![Build](https://github.com/avast/gradle-docker-compose-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/avast/gradle-docker-compose-plugin/actions/workflows/build.yml) [![Version](https://badgen.net/maven/v/maven-central/com.avast.gradle/gradle-docker-compose-plugin/)](https://repo1.maven.org/maven2/com/avast/gradle/gradle-docker-compose-plugin/)

Simplifies usage of [Docker Compose](https://docs.docker.com/compose/) for local development and integration testing in [Gradle](https://gradle.org/) environment.

`composeUp` task starts the application and waits till all containers become [healthy](https://docs.docker.com/engine/reference/builder/#healthcheck) and all exposed TCP ports are open (so till the application is ready). It reads assigned host and ports of particular containers and stores them into `dockerCompose.servicesInfos` property.

`composeDown` task stops the application and removes the containers, only if 'stopContainers' is set to 'true' (default value).

`composeDownForced` task stops the application and removes the containers.

`composePull` task pulls and optionally builds the images required by the application. This is useful, for example, with a CI platform that caches docker images to decrease build times.

`composeBuild` task builds the services of the application.

`composePush` task pushes images for services to their respective `registry/repository`.

`composeLogs` task stores logs from all containers to files in `containerLogToDir` directory.

## Quick start
The plugin is published to [Gradle Plugin Portal](https://plugins.gradle.org/plugin/com.avast.gradle.docker-compose), so the import is easy as

```gradle
plugins {
  id "com.avast.gradle.docker-compose" version "$versionHere"
}
```

Since the version `0.14.2`, the plugin is also published to Maven Central, so if your prefer this way:

```gradle
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath "com.avast.gradle:gradle-docker-compose-plugin:$versionHere"
    }
}

apply plugin: 'docker-compose'
```

> Versions prior to `0.14.2` were published to JCenter, and it is decommisioned now, so these old versions are not available.

After importing the plugin, the basic usage is typically just:
```gradle
dockerCompose.isRequiredBy(test)
```

It ensures:
* `docker-compose up` is executed in the project directory, so it uses the `docker-compose.yml` file.
* If the provided task (`test` in the example above) executes a new process then environment variables and Java system properties are provided.
  * The name of environment variable is `${serviceName}_HOST` and `${serviceName}_TCP_${exposedPort}` (e.g. `WEB_HOST` and `WEB_TCP_80`).
  * The name of Java system property is `${serviceName}.host` and `${serviceName}.tcp.${exposedPort}` (e.g. `web.host` and `web.tcp.80`).
  * If the service is scaled then the `serviceName` has `_1`, `_2`... suffix (e.g. `WEB_1_HOST` and `WEB_1_TCP_80`, `web_1.host` and `web_1.tcp.80`).
    * Please note that in Docker Compose v2, the suffix contains `-` instead of `_`

## Why to use Docker Compose?
1. I want to be able to run my application on my computer, and it must work for my colleagues as well. Just execute `docker compose up` and I'm done - e.g. the database is running. 
2. I want to be able to test my application on my computer - I don't wanna wait till my application is deployed into dev/testing environment and acceptance/end2end tests get executed. I want to execute these tests on my computer - it means execute `docker compose up` before these tests.

## Why this plugin?
You could easily ensure that `docker compose up` is called before your tests but there are few gotchas that this plugin solves:

1. If you execute `docker compose up -d` (_detached_) then this command returns immediately and your application is probably not able to serve requests at this time. This plugin waits till all containers become [healthy](https://docs.docker.com/engine/reference/builder/#healthcheck) and all exported TCP ports of all services are open.
   - If waiting for healthy state or open TCP ports timeouts (default is 15 minutes) then it prints log of related service. 
2. It's recommended not to assign fixed values of exposed ports in `docker-compose.yml` (i.e. `8888:80`) because it can cause ports collision on integration servers. If you don't assign a fixed value for exposed port (use just `80`) then the port is exposed as a random free port. This plugin reads assigned ports (and even IP addresses of containers) and stores them into `dockerCompose.servicesInfo` map.
3. There are minor differences when using Linux containers on Linux, Windows and Mac, and when using Windows Containers. This plugin handles these differences for you so you have the same experience in all environments.

# Usage
The plugin must be applied on project that contains `docker-compose.yml` file. It supposes that [Docker Engine](https://docs.docker.com/engine/) and [Docker Compose](https://docs.docker.com/compose/) are installed and available in `PATH`.

> Starting from plugin version _0.17.13_, Gradle 9.0 is required. Otherwise, you can experience issues related to missing `org/apache/groovy/runtime/ObjectUtil.`.

> Starting from plugin version _0.17.6_, Gradle 6.1 is required, because _Task.usesService()_ is used.

> Starting from plugin version _0.17.0_, _useDockerComposeV2_ property defaults to _true_, so the new `docker compose` (instead of deprecated `docker-compose` is used).

> Starting from plugin version _0.10.0_, Gradle 4.9 or newer is required (because it uses [Task Configuration Avoidance API](https://docs.gradle.org/current/userguide/task_configuration_avoidance.html)).

```gradle
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath "com.avast.gradle:gradle-docker-compose-plugin:$versionHere"
    }
}

apply plugin: 'docker-compose'

dockerCompose.isRequiredBy(test) // hooks 'dependsOn composeUp' and 'finalizedBy composeDown', and exposes environment variables and system properties (if possible)

dockerCompose {
    useComposeFiles = ['docker-compose.yml', 'docker-compose.prod.yml'] // like 'docker-compose -f <file>'; default is empty
    startedServices = ['web'] // list of services to execute when calling 'docker-compose up' or 'docker-compose pull' (when not specified, all services are executed)
    scale = [${serviceName1}: 5, ${serviceName2}: 2] // Pass docker compose --scale option like 'docker-compose up --scale serviceName1=5 --scale serviceName2=2'
    forceRecreate = false // pass '--force-recreate' and '--renew-anon-volumes' when calling 'docker-compose up' when set to 'true`
    noRecreate = false // pass '--no-recreate' when calling 'docker-compose up' when set to 'true`
    buildBeforeUp = true // performs 'docker-compose build' before calling the 'up' command; default is true
    buildBeforePull = true // performs 'docker-compose build' before calling the 'pull' command; default is true
    ignorePullFailure = false // when set to true, pass '--ignore-pull-failure' to 'docker-compose pull'
    ignorePushFailure = false // when set to true, pass '--ignore-push-failure' to 'docker-compose push'
    pushServices = [] // which services should be pushed, if not defined then upon `composePush` task all defined services in compose file will be pushed (default behaviour)
    buildAdditionalArgs = ['--force-rm']
    pullAdditionalArgs = ['--ignore-pull-failures']
    upAdditionalArgs = ['--no-deps']
    downAdditionalArgs = ['--some-switch']
    composeAdditionalArgs = ['--context', 'remote', '--verbose', "--log-level", "DEBUG"] // for adding more [options] in docker-compose [-f <arg>...] [options] [COMMAND] [ARGS...]

    waitForTcpPorts = true // turns on/off the waiting for exposed TCP ports opening; default is true
    waitForTcpPortsTimeout = java.time.Duration.ofMinutes(15) // how long to wait until all exposed TCP become open; default is 15 minutes
    waitAfterTcpProbeFailure = java.time.Duration.ofSeconds(1) // how long to sleep before next attempt to check if a TCP is open; default is 1 second
    tcpPortsToIgnoreWhenWaiting = [1234] // list of TCP ports what will be ignored when waiting for exposed TCP ports opening; default: empty list
    waitForHealthyStateTimeout = java.time.Duration.ofMinutes(15) // how long to wait until a container becomes healthy; default is 15 minutes
    waitAfterHealthyStateProbeFailure = java.time.Duration.ofSeconds(5) // how long to sleep before next attempt to check healthy status; default is 5 seconds
    checkContainersRunning = true // turns on/off checking if container is running or restarting (during waiting for open TCP port and healthy state); default is true

    captureContainersOutput = false // if true, prints output of all containers to Gradle output - very useful for debugging; default is false
    captureContainersOutputToFile = project.file('/path/to/logFile') // sends output of all containers to a log file
    captureContainersOutputToFiles = project.file('/path/to/directory') // sends output of all services to a dedicated log file in the directory specified, e.g. 'web.log' for service named 'log'
    composeLogToFile = project.file('build/my-logs.txt') // redirect output of composeUp and composeDown tasks to this file; default is null (ouput is not redirected)
    containerLogToDir = project.file('build/logs') // directory where composeLogs task stores output of the containers; default: build/containers-logs
    includeDependencies = false // calculates services dependencies of startedServices and includes those when gathering logs or removing containers; default is false

    stopContainers = true // doesn't call `docker-compose down` if set to false - see below the paragraph about reconnecting; default is true
    removeContainers = true // default is true
    retainContainersOnStartupFailure = false // if set to true, skips running ComposeDownForced task when ComposeUp fails - useful for troubleshooting; default is false
    removeImages = com.avast.gradle.dockercompose.RemoveImages.None // Other accepted values are All and Local
    removeVolumes = true // default is true
    removeOrphans = false // removes containers for services not defined in the Compose file; default is false
    
    projectName = 'my-project' // allow to set custom docker-compose project name (defaults to a stable name derived from absolute path of the project and nested settings name), set to null to Docker Compose default (directory name)
    projectNamePrefix = 'my_prefix_' // allow to set custom prefix of docker-compose project name, the final project name has nested configuration name appended
    executable = '/path/to/docker-compose' // allow to set the base Docker Compose command (useful if not present in PATH). Defaults to `docker-compose`. Ignored if useDockerComposeV2 is set to true.
    useDockerComposeV2 = true // Use Docker Compose V2 instead of Docker Compose V1, default is true. If set to true, `dockerExecutable compose` is used for execution, so executable property is ignored.
    dockerExecutable = '/path/to/docker' // allow to set the path of the docker executable (useful if not present in PATH)
    dockerComposeWorkingDirectory = project.file('/path/where/docker-compose/is/invoked/from')
    dockerComposeStopTimeout = java.time.Duration.ofSeconds(20) // time before docker-compose sends SIGTERM to the running containers after the composeDown task has been started
    environment.put 'BACKEND_ADDRESS', '192.168.1.100' // environment variables to be used when calling 'docker-compose', e.g. for substitution in compose file
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
    def webInfo = dockerCompose.servicesInfos.web.firstContainer
    // in case scale option is used, dockerCompose.servicesInfos.containerInfos will contain information about all running containers of service. Particular container can be retrieved either by iterating the values of containerInfos map (key is service instance name, for example 'web_1')
    def webInfo = dockerCompose.servicesInfos.web.'web_1'
    // pass host and exposed TCP port 80 as custom-named Java System properties
    systemProperty 'myweb.host', webInfo.host
    systemProperty 'myweb.port', webInfo.ports[80]
    // it's possible to read information about exposed UDP ports using webInfo.updPorts[1234]
}
```

## Nested configurations
It is possible to create a new set of `ComposeUp`/`ComposeBuild`/`ComposePull`/`ComposeDown`/`ComposeDownForced`/`ComposePush` tasks using following syntax:
<details open>
<summary>Groovy</summary>

```groovy
dockerCompose {
    // settings as usual
    myNested {
        useComposeFiles = ['docker-compose-for-integration-tests.yml']
        isRequiredBy(project.tasks.myTask)
    }
}
```

</details>

* It creates `myNestedComposeUp`, `myNestedComposeBuild`, `myNestedComposePull`, `myNestedComposeDown`, `myNestedComposeDownForced` and `myNestedComposePush` tasks.
* It's possible to use all the settings as in the main `dockerCompose` block.
* Configuration of the nested settings defaults to the main `dockerCompose` settings (declared before the nested settings), except following properties: `projectName`, `startedServices`, `useComposeFiles`, `scale`, `captureContainersOutputToFile`, `captureContainersOutputToFiles`, `composeLogToFile`, `containerLogToDir`, `pushServices`

When exposing service info from `myNestedComposeUp` task into your task you should use following syntax:
```groovy
test.doFirst {
    dockerCompose.myNested.exposeAsEnvironment(test)
}
```

<details>
<summary>Kotlin</summary>

```kotlin
test.doFirst {
    dockerCompose.nested("myNested").exposeAsEnvironment(project.tasks.named("test").get())
}
```    

</details>

It's also possible to use this simplified syntax:
```gradle
dockerCompose {
    isRequiredByMyTask 'docker-compose-for-integration-tests.yml'
}
```

## Reconnecting
If you specify `stopContainers` to be `false` then the plugin automatically tries to reconnect to the containers from the previous run
 instead of calling `docker-compose up` again. Thanks to this, the startup can be very fast.

It's very handy in scenarios when you iterate quickly and e.g. don't want to wait for Postgres to start again and again.

Because you don't want to check-in this change to your VCS, you can take advantage of [this init.gradle](/init.gradle) [initialization script](https://docs.gradle.org/5.2/userguide/init_scripts.html) (in short, copy [this file](/init.gradle) to your `USER_HOME/.gradle/` directory).

## Usage from Kotlin DSL
This plugin can be used also from Kotlin DSL, see the example:
```kotlin
import com.avast.gradle.dockercompose.ComposeExtension
apply(plugin = "docker-compose")
configure<ComposeExtension> {
    includeDependencies.set(true)
    createNested("local").apply {
        setProjectName("foo")
        environment.putAll(mapOf("TAGS" to "feature-test,local"))
        startedServices.set(listOf("foo-api", "foo-integration"))
        upAdditionalArgs.set(listOf("--no-deps"))
    }
}
```

# Tips
* You can call `dockerCompose.isRequiredBy(anyTask)` for any task, for example for your custom `integrationTest` task.
* If some Dockerfile needs an artifact generated by Gradle then you can declare this dependency in a standard way, like `composeUp.dependsOn project(':my-app').distTar`
* All properties in `dockerCompose` have meaningful default values so you don't have to touch it. If you are interested then you can look at [ComposeSettings.groovy](/src/main/groovy/com/avast/gradle/dockercompose/ComposeSettings.groovy) for reference.
* `dockerCompose.servicesInfos` contains information about running containers so you must access this property after `composeUp` task is finished. So `doFirst` of your test task is perfect place where to access it.
* Plugin honours a `docker-compose.override.yml` file, but only when no files are specified with `useComposeFiles` (conform command-line behavior).
* Check [ContainerInfo.groovy](/src/main/groovy/com/avast/gradle/dockercompose/ContainerInfo.groovy) to see what you can know about running containers.
* You can determine the Docker host in your Gradle build (i.e. `docker-machine start`) and set the `DOCKER_HOST` environment variable for compose to use: `dockerCompose { environment.put 'DOCKER_HOST', '192.168.64.9' }`
* If the services executed by `docker-compose` are running on a specific host (different than Docker, like in CirceCI 2.0), then `SERVICES_HOST` environment variable can be used. This value will be used as the hostname where the services are expected to be listening.
* If you need to troubleshoot a failing ComposeUp task, set `retainContainersOnStartupFailure` to prevent containers from begin forcibly deleted. Does not override `removeContainers`, so if you run `ComposeDown`, it will not be affected.

