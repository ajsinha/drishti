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
package com.ash.drishti.server.bi;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.dataset.DatasetParser;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.ash.drishti.server.security.SecurityProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A declared mask always holds (a leak otherwise): with an installation that masks no field of its own ({@code drishti.security.redact}
 * empty), a column a BI dataset lists under {@code security.masked} is still masked for a reader without the raw power and shown to one
 * with it.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact=",
        "drishti.security.roles.plain.kinds[0]=counterparty",
        "drishti.security.roles.rawrole.kinds[0]=counterparty", "drishti.security.roles.rawrole.raw=true",
        "drishti.database.sqlite.file=${drishti.test.dir}/identity-${random.uuid}/identity.db"})
class DeclaredMasksTest {

    @Autowired Entitlements entitlements;
    @Autowired DatasetService datasets;
    @Autowired SecurityProperties security;

    @Test
    void aDatasetsMaskedColumnIsMaskedWithoutTheRawPowerEvenWhenTheInstallationMasksNothing() throws Exception {
        assertThat(security.redact()).isEmpty();
        Principal plain = new Principal("u-plain", List.of("plain"));
        Principal raw = new Principal("u-rawrole", List.of("rawrole"));
        assertThat(entitlements.raw(plain)).isTrue();                    // the installation's own masks: none for anyone
        assertThat(entitlements.rawPower(plain)).isFalse();
        assertThat(entitlements.rawPower(raw)).isTrue();
        Dataset ds = DatasetParser.parse(Files.readString(Path.of("../config/tables/bi/trading_risk_counterparties.table.yaml"))).dataset();
        String table = ds.tables().keySet().iterator().next();
        assertThat(datasets.masks(plain, ds).masked(table, "NAME")).isTrue();
        assertThat(datasets.masks(plain, ds).masked(table, "RATING")).isTrue();
        assertThat(datasets.masks(plain, ds).masked(table, "SECTOR")).isFalse();
        assertThat(datasets.masks(raw, ds).masked(table, "NAME")).isFalse();
    }
}
