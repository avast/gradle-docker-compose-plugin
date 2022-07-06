package com.avast.gradle.dockercompose

import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.internal.file.FileOperations
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.internal.UncheckedException
import org.gradle.process.ExecOperations
import org.gradle.process.ExecSpec
import org.gradle.util.VersionNumber
import org.yaml.snakeyaml.Yaml

import javax.inject.Inject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

abstract class ComposeExecutor implements BuildService<Parameters>, AutoCloseable {
    static interface Parameters extends BuildServiceParameters {
        abstract DirectoryProperty getProjectDirectory()
        abstract ListProperty<String> getStartedServices()
        abstract ListProperty<String> getUseComposeFiles()
        abstract Property<Boolean> getIncludeDependencies()
        abstract DirectoryProperty getDockerComposeWorkingDirectory()
        abstract MapProperty<String, Object> getEnvironment()
        abstract Property<String> getExecutable()
        abstract Property<String> getProjectName()
        abstract ListProperty<String> getComposeAdditionalArgs()
        abstract Property<Boolean> getRemoveOrphans()
        abstract MapProperty<String, Integer> getScale()
    }

    static Provider<ComposeExecutor> getInstance(Project project, ComposeSettings settings) {
        String serviceId = "${ComposeExecutor.class.canonicalName} $project.path ${settings.hashCode()}"
        return project.gradle.sharedServices.registerIfAbsent(serviceId, ComposeExecutor) {
            it.parameters.projectDirectory.set(project.layout.projectDirectory)
            it.parameters.startedServices.set(settings.startedServices)
            it.parameters.useComposeFiles.set(settings.useComposeFiles)
            it.parameters.includeDependencies.set(settings.includeDependencies)
            it.parameters.dockerComposeWorkingDirectory.set(settings.dockerComposeWorkingDirectory)
            it.parameters.environment.set(settings.environment)
            it.parameters.executable.set(settings.executable)
            it.parameters.projectName.set(settings.projectName)
            it.parameters.composeAdditionalArgs.set(settings.composeAdditionalArgs)
            it.parameters.removeOrphans.set(settings.removeOrphans)
            it.parameters.scale.set(settings.scale)
        }
    }

    @Inject
    abstract ExecOperations getExec()

    @Inject
    abstract FileOperations getFileOps()

    private static final Logger logger = Logging.getLogger(ComposeExecutor.class);

    void executeWithCustomOutputWithExitValue(OutputStream os, String... args) {
        executeWithCustomOutput(os, false, true, true, args)
    }

    void executeWithCustomOutputNoExitValue(OutputStream os, String... args) {
        executeWithCustomOutput(os, true, true, true, args)
    }

    void executeWithCustomOutput(OutputStream os, Boolean ignoreExitValue, Boolean noAnsi, Boolean captureStderr, String... args) {
        def er = exec.exec { ExecSpec e ->
            if (parameters.dockerComposeWorkingDirectory.isPresent()) {
                e.setWorkingDir(parameters.dockerComposeWorkingDirectory.get().asFile)
            } else {
                e.setWorkingDir(parameters.projectDirectory)
            }
            e.environment = System.getenv() + parameters.environment.get()
            def finalArgs = [parameters.executable.get()]
            finalArgs.addAll(parameters.composeAdditionalArgs.get())
            if (noAnsi) {
                if (version >= VersionNumber.parse('1.28.0')) {
                    finalArgs.addAll(['--ansi', 'never'])
                } else if (version >= VersionNumber.parse('1.16.0')) {
                    finalArgs.add('--no-ansi')
                }
            }
            finalArgs.addAll(parameters.useComposeFiles.get().collectMany { ['-f', it].asCollection() })
            String pn = parameters.projectName.getOrNull()
            if (pn) {
                finalArgs.addAll(['-p', pn])
            }
            finalArgs.addAll(args)
            e.commandLine finalArgs
            if (os != null) {
                e.standardOutput = os
                if (captureStderr) {
                    e.errorOutput = os
                }
            }
            e.ignoreExitValue = true
        }
        if (!ignoreExitValue && er.exitValue != 0) {
            def stdout = os != null ? os.toString().trim() : "N/A"
            throw new RuntimeException("Exit-code ${er.exitValue} when calling ${parameters.executable.get()}, stdout: $stdout")
        }
    }

    String execute(String... args) {
        new ByteArrayOutputStream().withStream { os ->
            executeWithCustomOutput(os, false, true, false, args)
            os.toString().trim()
        }
    }

    private String executeWithAnsi(String... args) {
        new ByteArrayOutputStream().withStream { os ->
            executeWithCustomOutput(os, false, false, false, args)
            os.toString().trim()
        }
    }

    private VersionNumber cachedVersion

    VersionNumber getVersion() {
        if (cachedVersion) return cachedVersion
        String rawVersion = executeWithAnsi('version', '--short')
        cachedVersion = VersionNumber.parse(rawVersion.startsWith('v') ? rawVersion.substring(1) : rawVersion)
    }

    private List<String> listComposeServices() {
        return execute('ps', '--services').readLines()
    }

    Iterable<String> getContainerIds(String serviceName) {
        // `docker-compose ps -q serviceName` returns an exit code of 1 when the service
        // doesn't exist.  To guard against this, check the service list first.

        def retryCount = 0
        def services = listComposeServices()
        while (!services.contains(serviceName)) {
            sleep(1000)
            retryCount += 1
            if (retryCount > 10) {
                return []
            }
            services = listComposeServices()
        }

        return execute('ps', '-q', serviceName).readLines()
    }

    private Set<WeakReference<Thread>> threadsToInterruptOnClose = ConcurrentHashMap.newKeySet()

    void captureContainersOutput(Closure<Void> logMethod, String... services) {
        // execute daemon thread that executes `docker-compose logs -f --no-color`
        // the -f arguments means `follow` and so this command ends when docker-compose finishes
        def t = Executors.defaultThreadFactory().newThread(new Runnable() {
            @Override
            void run() {
                def os = new OutputStream() {
                    ArrayList<Byte> buffer = new ArrayList<Byte>()

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
                try {
                    executeWithCustomOutput(os, true, true, true, 'logs', '-f', '--no-color', *services)
                } catch (InterruptedException e) {
                    logger.trace("Thread capturing container output has been interrupted, this is not an error", e)
                } catch (UncheckedException ue) {
                    if (ue.cause instanceof InterruptedException) {
                        // Gradle < 5.0 incorrectly wrapped InterruptedException to UncheckedException
                        logger.trace("Thread capturing container output has been interrupted, this is not an error", ue)
                    } else {
                        throw ue
                    }
                } finally {
                    os.close()
                }
            }
        })
        t.daemon = true
        t.start()
        threadsToInterruptOnClose.add(new WeakReference<Thread>(t))
    }

    @Override
    void close() throws Exception {
        threadsToInterruptOnClose.forEach {threadRef ->
            def thread = threadRef.get()
            if (thread != null) {
                thread.interrupt()
            }
        }
    }

    Iterable<String> getServiceNames() {
        if (!parameters.startedServices.get().empty) {
            if(parameters.includeDependencies.get())
            {
                def dependentServices = getDependentServices(parameters.startedServices.get()).toList()
                [*parameters.startedServices.get(), *dependentServices].unique()
            }
            else
            {
                parameters.startedServices.get()
            }
        } else if (version >= VersionNumber.parse('1.6.0')) {
            execute('config', '--services').readLines()
        } else {
            def composeFiles = parameters.useComposeFiles.get().empty ? getStandardComposeFiles() : getCustomComposeFiles()
            composeFiles.collectMany { composeFile ->
                def compose = (Map<String, Object>) (new Yaml().load(fileOps.file(composeFile).text))
                // if there is 'version' on top-level then information about services is in 'services' sub-tree
                compose.containsKey('version') ? ((Map) compose.get('services')).keySet() : compose.keySet()
            }.unique()
        }
    }

    /**
     * Calculates dependent services for the given set of services. The full dependency graph will be calculated, such that transitive dependencies will be returned.
     * @param serviceNames the name of services to calculate dependencies for
     * @return the set of services that are dependencies of the given services
     */
    Iterable<String> getDependentServices(Iterable<String> serviceNames) {
        def configOutput = execute('config')
        def dependencyGraph = ComposeConfigParser.findServiceDependencies(configOutput)
        serviceNames.collectMany { dependencyGraph.getOrDefault(it, [].toSet()) }
    }

    Iterable<File> getStandardComposeFiles() {
        File searchDirectory = fileOps.file(parameters.dockerComposeWorkingDirectory) ?: parameters.projectDirectory.getAsFile()
        def res = []
        def f = findInParentDirectories('docker-compose.yml', searchDirectory)
        if (f != null) res.add(f)
        f = findInParentDirectories('docker-compose.override.yml', searchDirectory)
        if (f != null) res.add(f)
        res
    }

    Iterable<File> getCustomComposeFiles() {
        parameters.useComposeFiles.get().collect {
            def f = fileOps.file(it)
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

    boolean shouldRemoveOrphans() {
        version >= VersionNumber.parse('1.7.0') && parameters.removeOrphans.get()
    }

    boolean isScaleSupported() {
        def v = version
        if (v < VersionNumber.parse('1.13.0') && parameters.scale) {
            throw new UnsupportedOperationException("docker-compose version $v doesn't support --scale option")
        }
        !parameters.scale.get().isEmpty()
    }
}
