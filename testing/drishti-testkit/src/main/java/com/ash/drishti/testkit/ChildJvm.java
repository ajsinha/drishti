/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.testkit;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A child JVM for a test that must see a fresh Java (a start-up flag, a fresh hash salt, what a class loads): this JVM's own
 * {@code java}, with the test's class path handed over in an argument file ({@code java @file}) and never on the command line.
 * A Maven test class path is tens of thousands of characters; Windows refuses a command line over 32,767 characters
 * ({@code CreateProcess error=206}), so a class path on the command line works on Linux and fails on Windows.
 */
public final class ChildJvm {

    private ChildJvm() {
    }

    /** {@code java <jvmFlags> @<class path file> <mainClass> <args>}, ready to start (environment as inherited). */
    public static ProcessBuilder of(List<String> jvmFlags, String mainClass, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(java());
        cmd.addAll(jvmFlags);
        cmd.add("@" + classPathFile());
        cmd.add(mainClass);
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd);
    }

    /** This JVM's {@code java} executable. */
    public static String java() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** An argument file holding {@code -cp <this JVM's class path>}, quoted as the launcher reads it; removed when this JVM ends. */
    static Path classPathFile() {
        try {
            Path f = Files.createTempFile("drishti-child-cp-", ".args");
            f.toFile().deleteOnExit();
            Files.writeString(f, "-cp " + quoted(System.getProperty("java.class.path")) + "\n", StandardCharsets.UTF_8);
            return f;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One argument-file token: in double quotes, with a backslash and a double quote escaped (the launcher's rules). */
    static String quoted(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
