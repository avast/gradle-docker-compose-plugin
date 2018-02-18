package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeSettings
import com.avast.gradle.dockercompose.ContainerInfo
import com.avast.gradle.dockercompose.ServiceHost
import com.avast.gradle.dockercompose.ServiceInfo
import groovy.json.JsonBuilder
import groovy.json.JsonSlurper

import java.nio.file.Files
import java.util.function.Supplier

class ServiceInfoCache {
    private final ComposeSettings settings
    private final File servicesInfosFile
    private final File stateFile

    ServiceInfoCache(ComposeSettings settings) {
        this.settings = settings
        this.servicesInfosFile = new File(settings.project.buildDir, "dockerCompose/servicesInfos.json")
        this.stateFile = new File(settings.project.buildDir, "dockerCompose/state.txt")
    }

    Map<String, ServiceInfo> get(Supplier<String> stateSupplier) {
        if (servicesInfosFile.exists() && stateFile.exists()) {
            Map<String, Object> deserialized = new JsonSlurper().parse(servicesInfosFile)
            String cachedState = stateFile.text
            String currentState = stateSupplier.get()
            if (cachedState == currentState) {
                return deserialized.collectEntries { k, v -> [k, deserializeServiceInfo(v)] }
            } else {
                settings.project.logger.lifecycle("Current and cached states differs, cannot use the cached service infos.\nCached state:\n$cachedState\nCurrent state:\n$currentState")
            }
        }
        return null
    }

    void set(Map<String, ServiceInfo> servicesInfos, String state) {
        Files.createDirectories(servicesInfosFile.parentFile.toPath())
        servicesInfosFile.createNewFile()
        servicesInfosFile.text = new JsonBuilder(servicesInfos).toPrettyString()
        stateFile.createNewFile()
        stateFile.text = state
    }

    void clear() {
        servicesInfosFile.delete()
        stateFile.delete()
    }

    ServiceInfo deserializeServiceInfo(Map<String, Object> m) {
        Map<String, Object> ci = m.containerInfos
        new ServiceInfo(m.name, ci.collectEntries { k, v -> [(k): deserializeContainerInfo(v)] })
    }

    ContainerInfo deserializeContainerInfo(Map<String, Object> m) {
        Map<Integer, Integer> tcpPorts = m.tcpPorts.collectEntries { k, v -> [(Integer.parseInt(k)): v] }
        new ContainerInfo(m.instanceName, new ServiceHost(m.serviceHost as HashMap), tcpPorts, m.inspection)
    }
}
