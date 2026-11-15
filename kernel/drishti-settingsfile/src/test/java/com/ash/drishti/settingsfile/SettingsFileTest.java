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
package com.ash.drishti.settingsfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsFileTest {

    @AfterEach
    void clean() {
        for (String k : new String[] {SettingsFile.PROPERTY, "sf.test.a", "sf.test.b", "sf.test.c", "lake.risk.root", "lake.trading.root",
                "db.reporting.url", "db.reporting.password", "connector.trade-murex.lake"}) System.clearProperty(k);
        SettingsFile.reset();
    }

    private Path write(Path dir, String text) throws Exception {
        Path f = dir.resolve("drishti.ini");
        Files.writeString(f, text);
        return f;
    }

    @Test
    void entriesBecomePropertiesAndSectionsAndCommentsAreIgnored(@TempDir Path dir) throws Exception {
        System.setProperty(SettingsFile.PROPERTY, write(dir, "# a comment\n; another\n[paths]\nsf.test.a = /x/y\n\n[more]\nsf.test.b=1=2\n").toString());
        assertThat(SettingsFile.applyFromSystemProperty()).containsExactly("sf.test.a", "sf.test.b");
        assertThat(System.getProperty("sf.test.a")).isEqualTo("/x/y");
        assertThat(System.getProperty("sf.test.b")).as("the first = splits; the value keeps the rest").isEqualTo("1=2");
    }

    @Test
    void anExplicitPropertyBeatsTheFile(@TempDir Path dir) throws Exception {
        System.setProperty("sf.test.a", "explicit");
        System.setProperty(SettingsFile.PROPERTY, write(dir, "sf.test.a=fromfile\nsf.test.c=3\n").toString());
        assertThat(SettingsFile.applyFromSystemProperty()).containsExactly("sf.test.c");
        assertThat(System.getProperty("sf.test.a")).isEqualTo("explicit");
        assertThat(System.getProperty("sf.test.c")).isEqualTo("3");
        assertThat(SettingsFile.applyFromSystemProperty()).as("again: nothing left to set").isEmpty();
    }

    @Test
    void noPropertyMeansNothingIsLookedFor() {
        assertThat(SettingsFile.applyFromSystemProperty()).isEmpty();
    }

    @Test
    void aRelativePathIsRefused() {
        System.setProperty(SettingsFile.PROPERTY, "drishti.ini");
        assertThatThrownBy(SettingsFile::applyFromSystemProperty).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute");
    }

    @Test
    void aMissingFileAndABadLineAreClearErrors(@TempDir Path dir) throws Exception {
        System.setProperty(SettingsFile.PROPERTY, dir.resolve("nope.ini").toString());
        assertThatThrownBy(SettingsFile::applyFromSystemProperty).isInstanceOf(IllegalStateException.class).hasMessageContaining("not a file");
        System.setProperty(SettingsFile.PROPERTY, write(dir, "no equals sign\n").toString());
        assertThatThrownBy(SettingsFile::applyFromSystemProperty).isInstanceOf(IllegalStateException.class).hasMessageContaining(":1:");
    }

    @Test
    void aDottedSectionIsANamespaceSoOneLakeNeverReadsAnothers(@TempDir Path dir) throws Exception {
        Path f = write(dir, "[lake.risk]\nroot=/lakes/risk\n[lake.trading]\nroot=/lakes/trading\n[roots]\nsf.test.a=/full/name\n");
        assertThat(SettingsFile.read(f.toString())).containsEntry("lake.risk.root", "/lakes/risk").containsEntry("lake.trading.root", "/lakes/trading")
                .as("a section without a dot is a label: full names").containsEntry("sf.test.a", "/full/name");
    }

    @Test
    void severalFilesALaterOneWinsAndAnExplicitPropertyBeatsThemAll(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("base.ini");
        Path secrets = dir.resolve("secrets.ini");
        Files.writeString(base, "[db.reporting]\nurl=jdbc:postgresql://db/rep\npassword=changeme\n[lake.risk]\nroot=/a\n");
        Files.writeString(secrets, "[db.reporting]\npassword=s3cret\n");
        System.setProperty("lake.risk.root", "/explicit");
        System.setProperty(SettingsFile.PROPERTY, base + " , " + secrets);
        assertThat(SettingsFile.applyFromSystemProperty()).containsExactly("db.reporting.url", "db.reporting.password");
        assertThat(System.getProperty("db.reporting.password")).isEqualTo("s3cret");
        assertThat(System.getProperty("lake.risk.root")).isEqualTo("/explicit");
        assertThat(SettingsFile.sourceOf("db.reporting.password")).isEqualTo(secrets.toString());
        assertThat(SettingsFile.sourceOf("db.reporting.url")).isEqualTo(base.toString());
        assertThat(SettingsFile.filesRead()).containsExactly(base.toString(), secrets.toString());
    }

    @Test
    void secretsAreMasked() {
        assertThat(SettingsFile.shown("db.reporting.password", "s3cret")).isEqualTo(SettingsFile.MASK);
        assertThat(SettingsFile.shown("broker.kafka-eu.sasl-token", "t")).isEqualTo(SettingsFile.MASK);
        assertThat(SettingsFile.shown("lake.risk.root", "/a")).isEqualTo("/a");
        assertThat(SettingsFile.shown("db.reporting.password", "")).isEmpty();
    }

    @Test
    void aRelativePathInAListIsRefused(@TempDir Path dir) throws Exception {
        System.setProperty(SettingsFile.PROPERTY, write(dir, "a.b=1\n") + ",secrets.ini");
        assertThatThrownBy(SettingsFile::applyFromSystemProperty).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("absolute");
    }
}
