package com.avast.gradle.dockercompose


import groovy.json.JsonBuilder
import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

import java.nio.file.Files
import java.util.function.Supplier

abstract class ServiceInfoCache implements BuildService<Parameters> {
    static interface Parameters extends BuildServiceParameters {
        abstract RegularFileProperty getServicesInfosFile()
        abstract RegularFileProperty getStateFile()
    }

    static Provider<ServiceInfoCache> getInstance(Project project, String nestedName) {
        String serviceId = "${ServiceInfoCache.class.canonicalName} $project.path $nestedName"
        return project.gradle.sharedServices.registerIfAbsent(serviceId, ServiceInfoCache) {
            def buildDirectory = project.layout.buildDirectory
            it.parameters.servicesInfosFile = buildDirectory.file("dockerCompose/${nestedName}servicesInfos.json")
            it.parameters.stateFile = buildDirectory.file("dockerCompose/${nestedName}state.txt")
        }
    }

    private static final Logger logger = Logging.getLogger(ServiceInfoCache.class)

    private File getServicesInfosFile() {
        return parameters.servicesInfosFile.asFile.get()
    }

    private File getStateFile() {
        parameters.stateFile.asFile.get()
    }

    Map<String, ServiceInfo> get(Supplier<String> stateSupplier) {
        if (servicesInfosFile.exists() && stateFile.exists()) {
            Map<String, Object> deserialized = new JsonSlurper().parse(servicesInfosFile)
            String cachedState = stateFile.text
            String currentState = stateSupplier.get()
            if (cachedState == currentState) {
                return deserialized.collectEntries { k, v -> [k, deserializeServiceInfo(v)] }
            } else {
                logger.lifecycle("Current and cached states are different, cannot use the cached service infos.")
                logger.info("Cached state:\n$cachedState\nCurrent state:\n$currentState")
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
        Map<Integer, Integer> udpPorts = m.udpPorts.collectEntries { k, v -> [(Integer.parseInt(k)): v] }
        new ContainerInfo(instanceName: m.instanceName, serviceHost: new ServiceHost(m.serviceHost as HashMap), tcpPorts: tcpPorts, udpPorts: udpPorts, inspection: m.inspection)
    }

    boolean startupFailed = false
}
