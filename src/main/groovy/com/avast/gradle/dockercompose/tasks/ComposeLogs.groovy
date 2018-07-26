package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeSettings
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

class ComposeLogs extends DefaultTask {

  ComposeSettings settings

  ComposeLogs() {
    group = 'docker'
    description = 'Displays log output from services in containers of docker-compose project'
  }

  @TaskAction
  void logs() {

    if( !settings.containerLogToDir ) {
      println 'Not recording container logs: containerLogToDir not specified.'
      return
    }

    settings.composeExecutor.serviceNames.each { service ->
      println "Extracting container log from service '${service}'"
      new File(settings.containerLogToDir).mkdirs()
      def logStream = new FileOutputStream("${settings.containerLogToDir}/${service}.log")
      String[] args = ['logs', '-t', service]
      settings.composeExecutor.executeWithCustomOutputWithExitValue(logStream, args)
    }
  }
}
