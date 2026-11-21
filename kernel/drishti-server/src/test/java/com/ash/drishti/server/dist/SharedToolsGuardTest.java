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
package com.ash.drishti.server.dist;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.testroots.TestRoots;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * One way per job, held by a test (the shared utilities are Drishti Tools, {@code github.com/ajsinha/drishti-tools}): no main source of
 * this repository writes an Excel workbook's parts by itself, and none carries a copy of what the tools hold (TLS and the proxy, the
 * settings file and the placeholders, single-flight, the load guard, the path rule). The pin of the tools' version is one number, in the
 * parent pom and in the one standalone module that cannot import the parent's management.
 */
class SharedToolsGuardTest {
    private static final Set<String> COPIES = Set.of("TlsContexts.java", "TlsSettings.java", "TlsMaterial.java", "TlsHttp.java", "PemReader.java",
            "ProxySettings.java", "NoProxy.java", "SiteProxy.java", "Placeholders.java", "SettingsFile.java", "SingleFlight.java", "LoadGuard.java",
            "FakeProxy.java", "TlsFixture.java", "TestPki.java", "XlsxWriter.java", "XlsxCharts.java", "XlsxFormulas.java", "SheetStream.java",
            "WorkbookStream.java");

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> s = Files.walk(Path.of(TestRoots.repo()))) {
            return s.filter(p -> p.toString().endsWith(".java") && p.toString().contains("/src/main/java/") && !p.toString().contains("/target/")).toList();
        }
    }

    @Test
    void noMainSourceWritesWorkbookPartsOrCarriesACopyOfTheTools() throws IOException {
        for (Path p : mainSources()) {
            assertThat(COPIES).as(p + " is a copy of what Drishti Tools holds").doesNotContain(p.getFileName().toString());
            String text = Files.readString(p, StandardCharsets.UTF_8);
            assertThat(text).as(p + " writes workbook parts: use tools-xlsx").doesNotContain("xl/worksheets/").doesNotContain("[Content_Types].xml");
        }
    }

    @Test
    void theToolsVersionIsOneNumber() throws IOException {
        Pattern pin = Pattern.compile("<drishti.tools.version>([^<]+)</drishti.tools.version>");
        Matcher root = pin.matcher(Files.readString(Path.of(TestRoots.repo(), "pom.xml")));
        Matcher standalone = pin.matcher(Files.readString(Path.of(TestRoots.repo(), "testing/drishti-testroots/pom.xml")));
        assertThat(root.find()).isTrue();
        assertThat(standalone.find()).isTrue();
        assertThat(standalone.group(1)).as("testing/drishti-testroots pins the tools as the parent pom does").isEqualTo(root.group(1));
    }
}
