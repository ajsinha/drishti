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
package com.ash.drishti.module.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The rules of a module's Python (the {@code python:} section): entry points exist, requirements are vendored as pure-Python wheels, nothing is compiled. */
class PythonPartTest {

    @TempDir
    Path dir;

    private static void wheel(Path file, String... entries) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            for (int i = 0; i < entries.length; i += 2) {
                z.putNextEntry(new ZipEntry(entries[i]));
                z.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
    }

    private PythonPart part(String requires, String engines) {
        return new PythonPart("3.10", List.of("python"), "python/wheels", requires.isBlank() ? List.of() : List.of(requires), List.of(engines), Map.of());
    }

    private void engine() {
        Dmod.write(dir.resolve("python/calc/__init__.py"), "");
        Dmod.write(dir.resolve("python/calc/engine.py"), "class Square(Engine):\n    pass\n");
    }

    @Test
    void aCompleteModuleHasNoProblems() throws Exception {
        engine();
        wheel(dir.resolve("python/wheels/Tiny_Lib-1.0-py3-none-any.whl"), "tiny_lib/__init__.py", "X = 1\n");
        assertThat(part("tiny-lib==1.0", "calc.engine:Square").problems(dir)).isEmpty();
        assertThat(part("tiny.lib", "calc.engine:Square").problems(dir)).isEmpty();
    }

    @Test
    void aRequirementWithoutItsWheelIsAProblem() throws Exception {
        engine();
        wheel(dir.resolve("python/wheels/tinylib-1.0-py3-none-any.whl"), "tinylib/__init__.py", "X = 1\n");
        assertThat(part("tinylib==2.0", "calc.engine:Square").problems(dir)).singleElement().asString().contains("tinylib==2.0", "no wheel");
        assertThat(part("other", "calc.engine:Square").problems(dir)).singleElement().asString().contains("nothing is installed from a network");
    }

    @Test
    void nativeCodeIsRefusedInAWheelAndInThePackage() throws Exception {
        engine();
        wheel(dir.resolve("python/wheels/fast-1.0-cp312-cp312-linux_x86_64.whl"), "fast/_fast.so", "x");
        wheel(dir.resolve("python/wheels/sneaky-1.0-py3-none-any.whl"), "sneaky/_x.pyd", "x");
        Files.writeString(dir.resolve("python/calc/helper.dll"), "x");
        assertThat(part("", "calc.engine:Square").problems(dir)).anySatisfy(p -> assertThat(p).contains("not a pure-Python wheel"))
                .anySatisfy(p -> assertThat(p).contains("holds sneaky/_x.pyd"))
                .anySatisfy(p -> assertThat(p).contains("helper.dll is compiled or executable code"));
    }

    @Test
    void anEntryPointMustExist() throws Exception {
        engine();
        assertThat(part("", "calc.missing:Square").problems(dir)).singleElement().asString().contains("has no module calc.missing");
        assertThat(part("", "calc.engine:Cube").problems(dir)).singleElement().asString().contains("defines no class Cube");
        assertThat(part("", "not-an-entry").problems(dir)).singleElement().asString().contains("is not module:Class");
        assertThat(new PythonPart("3.10", List.of("nope"), "python/wheels", List.of(), List.of("calc.engine:Square"), Map.of()).problems(dir))
                .anySatisfy(p -> assertThat(p).contains("python.path names nope"));
    }

    @Test
    void theSectionIsReadFromTheManifest() {
        String yaml = """
                format: 1
                id: pyeng
                version: 1.0.0
                title: T
                about: A
                domain: d
                publisher: {name: P, id: p}
                licence: {feature: pyeng}
                kernel: ">=0.1 <1.0"
                rachana: ">=1.0"
                contracts: ">=1.0"
                plugin-api: ">=2.0"
                elements: ">=1.4 <2"
                python:
                  version: "3.11"
                  engines: [calc.engine:Square]
                  requires: [tinylib==1.0]
                """;
        DmodManifest m = DmodManifest.read(new java.io.ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), "test");
        PythonPart p = m.python().orElseThrow();
        assertThat(p.version()).isEqualTo("3.11");
        assertThat(p.path()).containsExactly("python");
        assertThat(p.wheels()).isEqualTo("python/wheels");
        assertThat(p.engines()).containsExactly("calc.engine:Square");
        assertThat(PythonPart.poolName("Pyeng_X")).isEqualTo("m-pyeng-x");
        DmodManifest bad = DmodManifest.read(new java.io.ByteArrayInputStream(yaml.replace("version: \"3.11\"", "version: 3").getBytes(StandardCharsets.UTF_8)), "test");
        assertThatThrownBy(bad::python).isInstanceOf(ModuleRefusedException.class).hasMessageContaining("python.version");
    }
}
