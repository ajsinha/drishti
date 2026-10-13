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
package com.ash.drishti.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalLibTest {

    @Test
    @SuppressWarnings("unchecked")
    void listsTheJarsOfTheJavaFolderUnderTheConfiguredBase(@TempDir Path base) throws Exception {
        Files.createDirectories(base.resolve("java"));
        Files.writeString(base.resolve("java/b-driver.jar"), "x");
        Files.writeString(base.resolve("java/a-lib.jar"), "xy");
        Files.writeString(base.resolve("java/notes.txt"), "not a jar");
        System.setProperty("drishti.external-lib", base.toString());
        try {
            Map<String, Object> info = ExternalLib.info();
            assertThat(info.get("base")).isEqualTo(base.toAbsolutePath().normalize().toString());
            List<Map<String, Object>> jars = (List<Map<String, Object>>) info.get("jars");
            assertThat(jars).extracting(j -> j.get("name")).containsExactly("a-lib.jar", "b-driver.jar");
            assertThat(jars.get(0).get("bytes")).isEqualTo(2L);
        } finally {
            System.clearProperty("drishti.external-lib");
        }
    }

    @Test
    void aMissingFolderListsNoJars(@TempDir Path base) {
        System.setProperty("drishti.external-lib", base.resolve("nowhere").toString());
        try {
            assertThat((List<?>) ExternalLib.info().get("jars")).isEmpty();
        } finally {
            System.clearProperty("drishti.external-lib");
        }
    }
}
