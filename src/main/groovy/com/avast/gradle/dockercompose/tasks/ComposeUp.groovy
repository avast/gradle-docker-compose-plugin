package com.avast.gradle.dockercompose.tasks

import com.avast.gradle.dockercompose.ComposeExtension
import com.avast.gradle.dockercompose.NoOpLogger
import com.avast.gradle.dockercompose.ServiceHost
import com.avast.gradle.dockercompose.ServiceHostType
import com.avast.gradle.dockercompose.ServiceInfo
import com.avast.gradle.dockercompose.ContainerInfo
import org.gradle.api.DefaultTask
import org.gradle.api.logging.Logger
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecSpec
import org.gradle.util.VersionNumber
import org.yaml.snakeyaml.Yaml

import java.time.Instant
import java.util.concurrent.Executors

class ComposeUp extends DefaultTask {

    ComposeExtension extension
    ComposeDown downTask

    private Map<String, ServiceInfo> servicesInfos = [:]

    Map<String, ServiceInfo> getServicesInfos() {
        servicesInfos
    }

    ComposeUp() {
        group = 'docker'
        description = 'Builds and starts all containers of docker-compose project'
    }

    @TaskAction
    void up() {
        if (extension.buildBeforeUp) {
            project.exec { ExecSpec e ->
                extension.setExecSpecWorkingDirectory(e)
                e.environment = extension.environment
                e.commandLine extension.composeCommand('build')
            }
        }
        project.exec { ExecSpec e ->
            extension.setExecSpecWorkingDirectory(e)
            e.environment = extension.environment
            String[] args = ['up', '-d']
            if (extension.removeOrphans()) {
                args += '--remove-orphans'
            }
            if (extension.forceRecreate) {
                args += '--force-recreate'
            }
            if (extension.scale()) {
                args += extension.scale.collect { service, value ->
                    ['--scale', "$service=$value"]
                }.flatten()
            }
            args += extension.startedServices
            e.commandLine extension.composeCommand(args)
        }
        try {
            if (extension.captureContainersOutput) {
                captureContainersOutput(logger.&lifecycle)
            }
            if (extension.captureContainersOutputToFile != null) {
                def logFile = extension.captureContainersOutputToFile
                logFile.parentFile.mkdirs()
                captureContainersOutput({ logFile.append(it + '\n') })
            }
            servicesInfos = loadServicesInfo().collectEntries { [(it.name): (it)] }
            waitForHealthyContainers(servicesInfos.values())
            if (extension.waitForTcpPorts) {
                waitForOpenTcpPorts(servicesInfos.values())
            }
        }
        catch (Exception e) {
            downTask.down()
            throw e
        }
    }

    protected void captureContainersOutput(Closure<Void> logMethod) {
        // execute daemon thread that executes `docker-compose logs -f --no-color`
        // the -f arguments means `follow` and so this command ends when docker-compose finishes
        def t = Executors.defaultThreadFactory().newThread(new Runnable() {
            @Override
            void run() {
                project.exec { ExecSpec e ->
                    extension.setExecSpecWorkingDirectory(e)
                    e.environment = extension.environment
                    e.commandLine extension.composeCommand('logs', '-f', '--no-color')
                    e.standardOutput = new OutputStream() {
                        def buffer = new ArrayList<Byte>()

                        @Override
                        void write(int b) throws IOException {
                            // store bytes into buffer until end-of-line character is detected
                            if (b == 10 || b == 13) {
                                if (buffer.size() > 0) {
                                    // convert the byte buffer to characters and print these characters
                                    def toPrint = buffer.collect { it as byte }.toArray() as byte[]
                                    logMethod(new String(toPrint))
                                    buffer.clear()
                                }
                            } else {
                                buffer.add(b as Byte)
                            }
                        }
                    }
                }
            }
        })
        t.daemon = true
        t.start()
    }

    protected Iterable<ServiceInfo> loadServicesInfo() {
        getServiceNames().collect { createServiceInfo(it) }
    }

    protected ServiceInfo createServiceInfo(String serviceName) {
        Iterable<String> containerIds = getContainerIds(serviceName)
        Map<String, ContainerInfo> containerInfos = createContainerInfos(containerIds, serviceName)
        new ServiceInfo(name: serviceName, containerInfos: containerInfos)
    }

    Map<String, ContainerInfo> createContainerInfos(Iterable<String> containerIds, String serviceName) {
        containerIds.collectEntries { String containerId ->
            logger.info("Container ID of service $serviceName is $containerId")
            def inspection = getValidDockerInspection(serviceName, containerId, 10)
            ServiceHost host = getServiceHost(serviceName, inspection)
            logger.info("Will use $host as host of service $serviceName")
            def tcpPorts = getTcpPortsMapping(serviceName, inspection, host)
            String instanceName = inspection.Name.find(/${serviceName}_\d+/) ?: inspection.Name - '/'
            [(instanceName): new ContainerInfo(
                                instanceName: instanceName,
                                serviceHost: host,
                                tcpPorts: tcpPorts,
                                inspection: inspection)]
        }
    }

    Iterable<String> getServiceNames() {
        if (extension.getDockerComposeVersion() >= VersionNumber.parse('1.6.0')) {
            new ByteArrayOutputStream().withStream { os ->
                project.exec { ExecSpec e ->
                    extension.setExecSpecWorkingDirectory(e)
                    e.environment = extension.environment
                    e.commandLine extension.composeCommand('config', '--services')
                    e.standardOutput = os
                }
                os.toString().readLines()
            }
        } else {
            def composeFiles = extension.useComposeFiles.empty ? getStandardComposeFiles() : getCustomComposeFiles()
            composeFiles.collectMany { composeFile ->
                def compose = (Map<String, Object>) (new Yaml().load(project.file(composeFile).text))
                // if there is 'version' on top-level then information about services is in 'services' sub-tree
                compose.containsKey('version') ? ((Map) compose.get('services')).keySet() : compose.keySet()
            }.unique()

        }
    }

    Iterable<File> getStandardComposeFiles() {
        def res = []
        def f = findInParentDirectories('docker-compose.yml', project.projectDir)
        if (f != null) res.add(f)
        f = findInParentDirectories('docker-compose.override.yml', project.projectDir)
        if (f != null) res.add(f)
        res
    }

    Iterable<File> getCustomComposeFiles() {
        extension.useComposeFiles.collect {
            def f = project.file(it)
            if (!f.exists()) {
                throw new IllegalArgumentException("Custom Docker Compose file not found: $f")
            }
            f
        }
    }

    File findInParentDirectories(String filename, File directory) {
        if ((directory) == null) return null
        def f = new File(directory, filename)
        f.exists() ? f : findInParentDirectories(filename, directory.parentFile)
    }

    Iterable<String> getContainerIds(String serviceName) {
        new ByteArrayOutputStream().withStream { os ->
            project.exec { ExecSpec e ->
                extension.setExecSpecWorkingDirectory(e)
                e.environment = extension.environment
                e.commandLine extension.composeCommand('ps', '-q', serviceName)
                e.standardOutput = os
            }
            os.toString().readLines()
        }
    }

    Map<String, Object> getDockerInspection(String containerId) {
        new ByteArrayOutputStream().withStream { os ->
            project.exec { ExecSpec e ->
                e.environment = extension.environment
                e.commandLine extension.dockerCommand('inspect', containerId)
                e.standardOutput os
            }
            def inspectionAsString = os.toString()
            logger.debug("Inspection for container $containerId: $inspectionAsString")
            (new Yaml().load(inspectionAsString))[0] as Map<String, Object>
        }
    }

    Map<String, Object> getValidDockerInspection(String serviceName, String containerId, int remainingRetries) {
        def dockerInspection = getDockerInspection(containerId)
        def validationError = getDockerInspectionValidationError(serviceName, dockerInspection)
        if (validationError.empty) {
            dockerInspection
        } else {
            def msg = "Docker inspection of container $containerId (service $serviceName) is not valid: '$validationError'\n${dockerInspection.toString()}"
            if (remainingRetries <= 0) {
                throw new RuntimeException(msg)
            }
            logger.lifecycle("$msg Sleeping and trying again")
            Thread.sleep(10000)
            getValidDockerInspection(serviceName, containerId, remainingRetries - 1)
        }
    }

    private String getDockerInspectionValidationError(String serviceName, Map<String, Object> inspection) {
        ServiceHost serviceHost
        try {
            serviceHost = getServiceHost(serviceName, inspection, NoOpLogger.INSTANCE)
        } catch (Exception e) {
            def msg = "Error when getting service host of service $serviceName: ${e.message}"
            logger.warn(msg, e)
            return msg
        }
        if (serviceHost.type != ServiceHostType.Host) {
            Map<String, Object> portsFromConfig = inspection.Config.ExposedPorts ?: [:]
            Map<String, Object> portsFromNetwork = inspection.NetworkSettings.Ports
            def missingPorts = portsFromConfig.keySet().findAll { !portsFromNetwork.containsKey(it) }
            if (!missingPorts.empty) {
                def msg = "There ports of service $serviceName are declared as exposed but cannot be found in NetworkSetting: ${missingPorts.join(', ')}"
                logger.warn(msg)
                return msg
            }
        }
        return ""
    }

    ServiceHost getServiceHost(String serviceName, Map<String, Object> inspection, Logger logger = this.logger) {
        String servicesHost = extension.environment['SERVICES_HOST'] ?: System.getenv('SERVICES_HOST')
        if (servicesHost) {
            logger.lifecycle("SERVICES_HOST environment variable detected - will be used as hostname of service $serviceName ($servicesHost)'")
            return new ServiceHost(host: servicesHost, type: ServiceHostType.RemoteDockerHost)
        }
        String dockerHost = extension.environment['DOCKER_HOST'] ?: System.getenv('DOCKER_HOST')
        if (dockerHost) {
            def host = dockerHost.toURI().host ?: 'localhost'
            logger.lifecycle("DOCKER_HOST environment variable detected - will be used as hostname of service $serviceName ($host)'")
            new ServiceHost(host: host, type: ServiceHostType.RemoteDockerHost)
        } else if (isMac() || isWindows()) {
            logger.lifecycle("Will use localhost as host of $serviceName")
            new ServiceHost(host: 'localhost', type: ServiceHostType.LocalHost)
        } else {
            // read gateway of first containers network
            String gateway
            Map<String, Object> networkSettings = inspection.NetworkSettings
            Map<String, Object> networks = networkSettings.Networks
            if (networks && networks.every { it.key.toLowerCase().equals("host") }) {
                gateway = 'localhost'
                logger.lifecycle("Will use $gateway as host of $serviceName because it is using HOST network")
                return new ServiceHost(host: 'localhost', type: ServiceHostType.Host)
            } else if (networks && networks.size() > 0) {
                Map.Entry<String, Object> firstNetworkPair = networks.find()
                gateway = firstNetworkPair.value.Gateway
                logger.lifecycle("Will use $gateway (network ${firstNetworkPair.key}) as host of $serviceName")
            } else { // networks not specified (older Docker versions)
                gateway = networkSettings.Gateway
                logger.lifecycle("Will use $gateway as host of $serviceName")
            }
            if (!gateway) {
                throw new RuntimeException('Gateway cannot be obtained')
            }
            new ServiceHost(host: gateway, type: ServiceHostType.NetworkGateway)
        }
    }

    Map<Integer, Integer> getTcpPortsMapping(String serviceName, Map<String, Object> inspection, ServiceHost host) {
        Map<Integer, Integer> ports = [:]
        inspection.NetworkSettings.Ports.each { String exposedPortWithProtocol, forwardedPortsInfos ->
            def (String exposedPortAsString, String protocol) = exposedPortWithProtocol.split('/')
            if (!"tcp".equalsIgnoreCase(protocol)) {
                return // from closure
            }
            int exposedPort = exposedPortAsString as int
            if (!forwardedPortsInfos || forwardedPortsInfos.isEmpty()) {
                logger.debug("No forwarded TCP port for service '$serviceName:$exposedPort'")
            } else {
                switch (host.type) {
                    case ServiceHostType.LocalHost:
                    case ServiceHostType.NetworkGateway:
                    case ServiceHostType.RemoteDockerHost:
                        if (forwardedPortsInfos.size() > 1) {
                            logger.warn("More forwarded TCP ports for service '$serviceName:$exposedPort $forwardedPortsInfos'. Will use the first one.")
                        }
                        def forwardedPortInfo = forwardedPortsInfos.first()
                        int forwardedPort = forwardedPortInfo.HostPort as int
                        logger.info("Exposed TCP port on service '$serviceName:$exposedPort' will be available as $forwardedPort")
                        ports.put(exposedPort, forwardedPort)
                        break
                    case ServiceHostType.Host:
                        logger.info("Exposed TCP port on service '$serviceName:$exposedPort' will be available as $exposedPort because it uses HOST network")
                        ports.put(exposedPort, exposedPort)
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown ServiceHostType '${host.type}' for service '$serviceName'")
                        break
                }
            }
        }
        ports
    }

    void waitForHealthyContainers(Iterable<ServiceInfo> servicesInfos) {
        servicesInfos.forEach { serviceInfo ->
            serviceInfo.containerInfos.each { instanceName, containerInfo ->
                def start = Instant.now()
                while (true) {
                    Map<String, Object> inspectionState = getDockerInspection(containerInfo.containerId).State
                    if (inspectionState.containsKey('Health')) {
                        String healthStatus = inspectionState.Health.Status
                        if (!"starting".equalsIgnoreCase(healthStatus) && !"unhealthy".equalsIgnoreCase(healthStatus)) {
                            logger.lifecycle("${instanceName} health state reported as '$healthStatus' - continuing...")
                            return
                        }
                        logger.lifecycle("Waiting for ${instanceName} to become healthy (it's $healthStatus)")
                        sleep(extension.waitAfterHealthyStateProbeFailure.toMillis())
                    } else {
                        logger.debug("Service ${instanceName} or this version of Docker doesn't support healtchecks")
                        return
                    }
                    if (start.plus(extension.waitForHealthyStateTimeout) < Instant.now()) {
                        throw new RuntimeException("Container ${containerInfo.containerId} of service ${instanceName} is still reported as 'starting'. Logs:${System.lineSeparator()}${getServiceLogs(containerInfo.containerId)}")
                    }
                }
            }
        }
    }

    void waitForOpenTcpPorts(Iterable<ServiceInfo> servicesInfos) {
        servicesInfos.forEach { serviceInfo ->
            serviceInfo.containerInfos.each { instanceName, containerInfo ->
                containerInfo.tcpPorts.forEach { exposedPort, forwardedPort ->
                    logger.lifecycle("Probing TCP socket on ${containerInfo.host}:${forwardedPort} of service '${instanceName}'")
                    def start = Instant.now()
                    while (true) {
                        try {
                            def s = new Socket(containerInfo.host, forwardedPort)
                            s.setSoTimeout(extension.waitForTcpPortsDisconnectionProbeTimeout.toMillis() as int)
                            try {
                                // in case of Windows and Mac, we must ensure that the socket is not disconnected immediately
                                // if the socket is closed then it returns -1
                                // if the socket is not closed then returns a data or timeouts
                                boolean disconnected = false
                                try {
                                    disconnected = s.inputStream.read() == -1
                                } catch (Exception e) {
                                    logger.debug("An exception when reading from socket", e) // expected exception
                                }
                                if (disconnected) {
                                    throw new RuntimeException("TCP connection on ${containerInfo.host}:${forwardedPort} of service '${instanceName}' was disconnected right after connected")
                                }
                            }
                            finally {
                                s.close()
                            }
                            logger.lifecycle("TCP socket on ${containerInfo.host}:${forwardedPort} of service '${instanceName}' is ready")
                            return
                        }
                        catch (Exception e) {
                            if (start.plus(extension.waitForTcpPortsTimeout) < Instant.now()) {
                                throw new RuntimeException("TCP socket on ${containerInfo.host}:${forwardedPort} of service '${instanceName}' is still failing. Logs:${System.lineSeparator()}${getServiceLogs(containerInfo.containerId)}")
                            }
                            logger.lifecycle("Waiting for TCP socket on ${containerInfo.host}:${forwardedPort} of service '${instanceName}' (${e.message})")
                            sleep(extension.waitAfterTcpProbeFailure.toMillis())
                        }
                    }
                }
            }
        }
    }

    String getServiceLogs(String containerId) {
        new ByteArrayOutputStream().withStream { os ->
            project.exec { ExecSpec e ->
                e.environment = extension.environment
                e.commandLine extension.dockerCommand('logs', '--follow=false', containerId)
                e.standardOutput = os
            }
            os.toString().trim()
        }
    }

    private static boolean isMac() {
        System.getProperty("os.name").toLowerCase().startsWith("mac")
    }

    private static boolean isWindows() {
        System.getProperty("os.name").toLowerCase().startsWith("win")
    }
}
