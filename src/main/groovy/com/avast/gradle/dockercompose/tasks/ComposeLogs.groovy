package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExecutor
import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

@CompileStatic
abstract class ComposeLogs extends DefaultTask {

  @Internal
  abstract DirectoryProperty getContainerLogToDir()

  @Internal
  abstract Property<ComposeExecutor> getComposeExecutor()

  ComposeLogs() {
    group = 'docker'
    description = 'Stores log output from services in containers of docker-compose project'
  }

  @TaskAction
  void logs() {
    composeExecutor.get().serviceNames.each { service ->
      println "Extracting container log from service '${service}'"
      File logFile = containerLogToDir.get().asFile
      logFile.mkdirs()
      def logStream = new FileOutputStream("${logFile.absolutePath}/${service}.log")
      String[] args = ['logs', '-t', service]
      composeExecutor.get().executeWithCustomOutputWithExitValue(logStream, args)
    }
  }
}
