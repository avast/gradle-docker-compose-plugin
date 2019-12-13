package com.avast.gradle.dockercompose

import org.gradle.api.Project
import org.gradle.api.logging.Logger
import org.gradle.process.ExecSpec
import org.gradle.util.VersionNumber
import org.yaml.snakeyaml.Yaml

import java.util.concurrent.Executors

class ComposeExecutor {
    private final ComposeSettings settings
    private final Project project
    private final Logger logger

    ComposeExecutor(ComposeSettings settings) {
        this.settings = settings
        this.project = settings.project
        this.logger = settings.project.logger
    }

    void executeWithCustomOutputWithExitValue(OutputStream os, String... args) {
        executeWithCustomOutput(os, false, true, true, args)
    }

    void executeWithCustomOutputNoExitValue(OutputStream os, String... args) {
        executeWithCustomOutput(os, true, true, true, args)
    }

    void executeWithCustomOutput(OutputStream os, Boolean ignoreExitValue, Boolean noAnsi, Boolean captureStderr, String... args) {
        def ex = this.settings
        def er = project.exec { ExecSpec e ->
            if (settings.dockerComposeWorkingDirectory) {
                e.setWorkingDir(settings.dockerComposeWorkingDirectory)
            }
            e.environment = ex.environment
            def finalArgs = [ex.executable]
            if (noAnsi && version >= VersionNumber.parse("1.16.0")) {
                finalArgs.add('--no-ansi')
            }
            finalArgs.addAll(ex.useComposeFiles.collectMany { ['-f', it].asCollection() })
            if (ex.projectName) {
                finalArgs.addAll(['-p', ex.projectName])
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
            throw new RuntimeException("Exit-code ${er.exitValue} when calling ${settings.executable}, stdout: $stdout")
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
        cachedVersion = VersionNumber.parse(executeWithAnsi('version', '--short'))
    }

    Iterable<String> getContainerIds(String serviceName) {
        execute('ps', '-q', serviceName).readLines()
    }

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
                } finally {
                    os.close()
                }
            }
        })
        t.daemon = true
        t.start()
        project.gradle.buildFinished { t.interrupt() }
    }

    Iterable<String> getServiceNames() {
        if (!settings.startedServices.empty) {
            if(settings.includeDependencies)
            {
                def dependentServices = getDependentServices(settings.startedServices).toList()
                [*settings.startedServices, *dependentServices].unique()
            }
            else
            {
                settings.startedServices
            }
        } else if (version >= VersionNumber.parse('1.6.0')) {
            execute('config', '--services').readLines()
        } else {
            def composeFiles = settings.useComposeFiles.empty ? getStandardComposeFiles() : getCustomComposeFiles()
            composeFiles.collectMany { composeFile ->
                def compose = (Map<String, Object>) (new Yaml().load(project.file(composeFile).text))
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
        def res = []
        def f = findInParentDirectories('docker-compose.yml', project.projectDir)
        if (f != null) res.add(f)
        f = findInParentDirectories('docker-compose.override.yml', project.projectDir)
        if (f != null) res.add(f)
        res
    }

    Iterable<File> getCustomComposeFiles() {
        settings.useComposeFiles.collect {
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

}
