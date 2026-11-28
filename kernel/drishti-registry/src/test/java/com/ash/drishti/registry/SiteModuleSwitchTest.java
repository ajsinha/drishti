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
package com.ash.drishti.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Module switches: the release's shipped files are read first, the site's win, and a switch is written to the site folder only. */
class SiteModuleSwitchTest {

    @TempDir
    Path dir;

    @AfterEach
    void tearDown() {
        System.clearProperty("drishti.home");
        System.clearProperty("drishti.site.dir");
    }

    @Test
    void theSwitchIsWrittenToTheSiteAndWinsOverTheReleasesFile() throws Exception {
        Path release = Files.createDirectories(dir.resolve("release/config/modules"));
        Path site = Files.createDirectories(dir.resolve("etc-drishti/modules"));
        System.setProperty("drishti.home", dir.resolve("release").toString());
        System.setProperty("drishti.site.dir", dir.resolve("etc-drishti").toString());
        Files.writeString(release.resolve("extra.yaml"), "enabled: true\nmodule: { id: extra, domain: extra, title: Extra }\n");
        YamlAccessStore store = new YamlAccessStore(release, site, dir.resolve("etc-drishti/access"), null, () -> null);
        assertThat(store.read().modules()).anyMatch(m -> m.id().equals("extra"));          // declared by the release's file
        assertThat(store.read().switches().get("extra")).isTrue();
        long before = store.stamp();
        store.setEnabled("extra", false);
        assertThat(Files.readString(site.resolve("extra.yaml"))).contains("enabled: false");
        assertThat(Files.readString(release.resolve("extra.yaml"))).contains("enabled: true");   // the release's file is untouched
        assertThat(store.read().switches().get("extra")).isFalse();                                // the site wins
        assertThat(store.stamp()).isNotEqualTo(before);
        YamlAccessStore inRelease = new YamlAccessStore(release, release, dir.resolve("etc-drishti/access"), null, () -> null);
        assertThatThrownBy(() -> inRelease.setEnabled("extra", false)).hasMessageContaining("release folder");
    }
}
