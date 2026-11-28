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
package com.ash.drishti.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.error.ErrorCode;
import com.ash.drishti.api.error.DrishtiException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The site folder (docs/admin/PRODUCTION_STANDARD.md, "Site configuration"): one resolver, the release never written. */
class SiteFolderTest {

    @TempDir
    Path dir;
    Path release;
    Path site;

    @BeforeEach
    void setUp() throws Exception {
        release = Files.createDirectories(dir.resolve("release"));
        site = Files.createDirectories(dir.resolve("etc-drishti"));
        System.setProperty(Roots.HOME, release.toString());
        System.clearProperty(Roots.CONFIG);
        System.clearProperty(Roots.SITE);
        System.setProperty(DataDir.PROPERTY, dir.resolve("data").toString());
    }

    @AfterEach
    void tearDown() {
        for (String k : new String[] {Roots.HOME, Roots.CONFIG, Roots.SITE, DataDir.PROPERTY}) {
            System.clearProperty(k);
        }
    }

    /** No site folder named: the developer layout, the site folder is the configuration folder, nothing is refused. */
    @Test
    void theSiteFolderDefaultsToTheConfigurationFolder() throws Exception {
        assertThat(Roots.site()).isEqualTo(release.resolve("config").toString());
        assertThat(Roots.site("connectors")).isEqualTo(release.resolve("config/connectors").toString());
        assertThat(Roots.siteIsSeparate()).isFalse();
        assertThat(Roots.localFile()).isEqualTo(release.resolve("application.local.yaml"));
        Roots.guardWrite(release.resolve("config/connectors/x.yaml"));          // allowed
        assertThat(Roots.connectors()).isEqualTo(release.resolve("config/connectors"));
    }

    @Test
    void aNamedSiteFolderIsWhereEverySiteFileIsReadAndWritten() {
        System.setProperty(Roots.SITE, site.toString());
        assertThat(Roots.siteIsSeparate()).isTrue();
        assertThat(Roots.site("engines")).isEqualTo(site.resolve("engines").toString());
        assertThat(Roots.connectors()).isEqualTo(site.resolve("connectors"));
        assertThat(Roots.localFile()).isEqualTo(site.resolve("application.local.yaml"));
        assertThat(Roots.config("packs")).isEqualTo(release.resolve("config/packs").toString());   // the release's defaults stay where they are
    }

    @Test
    void aWriteIntoTheReleaseIsRefusedWithTheWayOut() {
        System.setProperty(Roots.SITE, site.toString());
        assertThatThrownBy(() -> Roots.guardWrite(release.resolve("config/connectors/trade-endur.yaml")))
                .isInstanceOf(Roots.ReleaseFolderWriteException.class).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("inside the release folder").hasMessageContaining(Roots.SITE).hasMessageContaining(site.toString());
        assertThat(ErrorCode.RELEASE_FOLDER_READ_ONLY.code()).isEqualTo("DRS-1038");
        Roots.guardWrite(site.resolve("connectors/trade-endur.yaml"));                  // the site folder: allowed
        Roots.guardWrite(dir.resolve("data/identity/x"));                               // the data folder: allowed
        Roots.guardWrite(dir.resolve("elsewhere/x"));                                   // outside the release: not this rule's business
    }

    @Test
    void theOneWriterRefusesToReplaceAFileInTheRelease() throws Exception {
        System.setProperty(Roots.SITE, site.toString());
        Path target = Files.createDirectories(release.resolve("config/engines")).resolve("var.yaml");
        Path tmp = Files.writeString(release.resolve("config/engines/var.yaml.tmp"), "x");
        assertThatThrownBy(() -> FileReplace.atomically(tmp, target)).isInstanceOf(Roots.ReleaseFolderWriteException.class);
        assertThat(target).doesNotExist();
        assertThat(tmp).doesNotExist();
        Path ok = Files.writeString(site.resolve("v.tmp"), "y");
        FileReplace.atomically(ok, site.resolve("var.yaml"));
        assertThat(Files.readString(site.resolve("var.yaml"))).isEqualTo("y");
    }
}
