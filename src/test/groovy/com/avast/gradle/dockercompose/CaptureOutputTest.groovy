package com.avast.gradle.dockercompose

import org.gradle.api.logging.LogLevel
import org.gradle.internal.logging.events.LogEvent
import org.gradle.internal.logging.events.OutputEvent
import org.gradle.internal.logging.events.OutputEventListener
import org.gradle.internal.logging.slf4j.Slf4jLoggingConfigurer
import spock.lang.Specification

class CaptureOutputTest extends Specification {

    private String composeFileContent = '''
            web:
                image: nginx
                command: bash -c "echo -e 'heres some output\\nand some more' && sleep 5 && nginx -g 'daemon off;'"
                ports:
                  - 80
        '''

    def "captures container output to stdout"() {
        def f = Fixture.custom(composeFileContent)
        def stdout = new StringBuffer()
        new Slf4jLoggingConfigurer(new OutputEventListener() {
            @Override
            void onOutput(OutputEvent outputEvent) {
                if (outputEvent instanceof LogEvent) {
                    stdout.append(((LogEvent) outputEvent).message + '\n')
                }
            }
        }).configure(LogLevel.LIFECYCLE)

        when:
        f.extension.captureContainersOutput = true
        f.project.tasks.composeUp.up()
        then:
        noExceptionThrown()
        stdout.toString().contains("web_1  | heres some output\nweb_1  | and some more")
        cleanup:
        f.project.tasks.composeDown.down()
        f.close()
    }

    def "captures container output to file"() {
        def f = Fixture.custom(composeFileContent)
        def logFile = new File(f.project.projectDir, "web.log")
        when:
        f.extension.captureContainersOutputToFile = logFile
        f.project.tasks.composeUp.up()
        then:
        noExceptionThrown()
        logFile.text.contains("web_1  | heres some output\nweb_1  | and some more")
        cleanup:
        f.project.tasks.composeDown.down()
        f.close()
    }

    def "captures container output to file path"() {
        def f = Fixture.custom(composeFileContent)
        def logFile = new File(f.project.projectDir, "web.log")
        when:
        f.extension.captureContainersOutputToFile = "${logFile.absolutePath}"
        f.project.tasks.composeUp.up()
        then:
        noExceptionThrown()
        logFile.text.contains("web_1  | heres some output\nweb_1  | and some more")
        cleanup:
        f.project.tasks.composeDown.down()
        f.close()
    }
}
