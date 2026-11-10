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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.PluginNotConfigured;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.testkit.DatedSourceContract;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The iceberg connector with {@code catalog: s3tables} (AWS S3 Tables): a file copied from {@code config/connectors.examples/s3tables/}
 * before the table bucket is named stays idle, the ARN becomes the REST settings before anything opens, and S3 Tables is always read by
 * the native engine (it signs its requests itself). The catalog side is in drishti-iceberg's {@code S3TablesTest} and
 * {@code S3TablesRestTest}; the live run is {@code GlueLiveTest.s3TablesCreatesWritesReadsAndDrops}.
 */
class IcebergS3TablesTest {

    static final String ARN = "arn:aws:s3tables:eu-west-2:111122223333:bucket/drishti-risk";

    @Test
    void aTableBucketNotNamedYetLeavesTheConnectorIdle() {
        IcebergSourcePlugin p = new IcebergSourcePlugin();
        assertThatThrownBy(() -> p.start(DatedSourceContract.context(Map.of("catalog", "s3tables", "table-bucket-arn", "", "domain", "risk"))))
                .isInstanceOf(PluginNotConfigured.class).hasMessageContaining("table-bucket-arn");
    }

    @Test
    void theArnBecomesTheRestSettingsBeforeAnythingOpens() {
        SourceContext ctx = IcebergSourcePlugin.s3Tables(DatedSourceContract.context(Map.of("catalog", "s3tables", "table-bucket-arn", ARN,
                "domain", "risk", "kinds", "pnl-vector,scenario-pv")));
        assertThat(ctx.setting("uri", "")).isEqualTo("https://s3tables.eu-west-2.amazonaws.com/iceberg");
        assertThat(ctx.settings()).containsEntry("warehouse", ARN).containsEntry("catalog.rest.signing-name", "s3tables")
                .containsEntry("catalog.rest.signing-region", "eu-west-2").containsEntry("s3.region", "eu-west-2")
                .containsEntry("namespace", "risk").containsEntry("kinds", "pnl-vector,scenario-pv");
        SourceContext plain = DatedSourceContract.context(Map.of("root", "/tmp/x"));
        assertThat(IcebergSourcePlugin.s3Tables(plain)).isSameAs(plain);
    }

    @Test
    void s3TablesIsReadByTheNativeEngine() {
    }
}
