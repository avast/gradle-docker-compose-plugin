package com.avast.gradle.dockercompose.util;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * This class is a simplified version of the deprecated org.gradle.util.VersionNumber class
 * See https://github.com/gradle/gradle/blob/7d8cacafe70e5c4cc06173550cb13511cfbf3749/subprojects/core/src/main/java/org/gradle/util/VersionNumber.java
 * causing compatibility issues with Gradle 8.1 onwards.
 */
public class VersionNumber implements Comparable<VersionNumber> {
    public static final VersionNumber UNKNOWN = new VersionNumber(0, 0, 0);

    private final int major;
    private final int minor;
    private final int micro;

    private VersionNumber(int major, int minor, int micro) {
        this.major = major;
        this.minor = minor;
        this.micro = micro;
    }

    @Override
    public int compareTo(VersionNumber other) {
        if (major != other.major) {
            return major - other.major;
        }
        if (minor != other.minor) {
            return minor - other.minor;
        }
        if (micro != other.micro) {
            return micro - other.micro;
        }
        return 0;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof VersionNumber && compareTo((VersionNumber) other) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, micro);
    }

    @Override
    public String toString() {
        return String.format("%d.%d.%d", major, minor, micro);
    }

    public static VersionNumber parse(String versionString) {
        if (versionString == null || versionString.length() == 0) {
            return UNKNOWN;
        }
        Scanner scanner = new Scanner(versionString);

        int major = 0;
        int minor = 0;
        int micro = 0;

        if (!scanner.hasDigit()) {
            return UNKNOWN;
        }
        major = scanner.scanDigit();
        if (scanner.isSeparatorAndDigit()) {
            scanner.skipSeparator();
            minor = scanner.scanDigit();
            if (scanner.isSeparatorAndDigit()) {
                scanner.skipSeparator();
                micro = scanner.scanDigit();
            }
        }

        if (scanner.isEnd() || scanner.hasSpecifierSeparator()) {
            return new VersionNumber(major, minor, micro);
        }

        return UNKNOWN;
    }

    private static class Scanner {
        int pos;
        final String str;

        private Scanner(String string) {
            this.str = string;
        }

        boolean hasDigit() {
            return pos < str.length() && Character.isDigit(str.charAt(pos));
        }

        boolean hasSpecifierSeparator() {
            return pos < str.length() && (str.charAt(pos) == '-' || str.charAt(pos) == '+');
        }

        boolean isSeparatorAndDigit() {
            return pos < str.length() - 1 && isSeparator() && Character.isDigit(str.charAt(pos + 1));
        }

        private boolean isSeparator() {
            return str.charAt(pos) == '.';
        }

        int scanDigit() {
            int start = pos;
            while (hasDigit()) {
                pos++;
            }
            return Integer.parseInt(str.substring(start, pos));
        }

        public boolean isEnd() {
            return pos == str.length();
        }

        public void skipSeparator() {
            pos++;
        }
    }
}
