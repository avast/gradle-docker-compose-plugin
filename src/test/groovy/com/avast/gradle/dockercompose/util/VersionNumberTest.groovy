package com.avast.gradle.dockercompose.util

import spock.lang.Specification
import spock.lang.Unroll

import static com.avast.gradle.dockercompose.util.VersionNumber.parse

class VersionNumberTest extends Specification {

    @Unroll
    def "can compare version #a and #b"() {
        expect:
        parse(a) <=> parse(b) == expected
        where:
        a        | b        | expected
        "0.0.1"  | "0.0.1"  | 0
        "0.0.1"  | "0.0.2"  | -1
        "0.0.2"  | "0.0.1"  | 1
        "0.1.0"  | "0.1.0"  | 0
        "0.1.0"  | "0.1.0"  | 0
        "1.1.1"  | "1.1.1"  | 0
        "1.1.0"  | "1.2.0"  | -1
        "1.28.0" | "1.16.0" | 1
        "2.20.2-desktop.1" | "2.20.2" | 0
        "2.20.2+azure-1" | "2.20.2" | 0
    }

    def "handles non parseable versions as UNKNOWN"() {
        expect:
        parse("SomeInvalid") == VersionNumber.UNKNOWN
    }
}
