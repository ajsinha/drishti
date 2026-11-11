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
package com.ash.drishti.iceberg.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S3 Tables without an AWS account: the settings a table bucket ARN stands for (endpoint, warehouse, prefix, SigV4 under the name
 * {@code s3tables}), S3 Tables' names, and the request signatures compared with ones recorded by botocore, the AWS SDK for Python
 * ({@code s3tables/sigv4-recorded.json}). No local stand-in of S3 Tables exists, so these recorded expectations, with the protocol
 * test against a stand-in REST server ({@link S3TablesRestTest}), are what is proven before a live run.
 */
class S3TablesTest {

    static final String ARN = "arn:aws:s3tables:us-east-1:111122223333:bucket/drishti-risk";
    static final String ENCODED = "arn%3Aaws%3As3tables%3Aus-east-1%3A111122223333%3Abucket%2Fdrishti-risk";

    @Test
    void anArnGivesTheBucketItsRegionAndTheEndpoint() {
        S3Tables.Bucket b = S3Tables.bucket(ARN);
        assertThat(b.region()).isEqualTo("us-east-1");
        assertThat(b.account()).isEqualTo("111122223333");
        assertThat(b.name()).isEqualTo("drishti-risk");
        assertThat(b.endpoint()).isEqualTo("https://s3tables.us-east-1.amazonaws.com/iceberg");
        assertThat(b.prefix()).isEqualTo(ENCODED);
        assertThat(S3Tables.bucket("arn:aws-cn:s3tables:cn-north-1:111122223333:bucket/lake").endpoint())
                .isEqualTo("https://s3tables.cn-north-1.amazonaws.com.cn/iceberg");
        assertThat(S3Tables.bucket("arn:aws-us-gov:s3tables:us-gov-west-1:111122223333:bucket/lake").region()).isEqualTo("us-gov-west-1");
    }

    @Test
    void whatIsNotATableBucketArnIsRefusedWithTheFormExpected() {
        for (String bad : new String[] {"", "drishti-risk", "arn:aws:s3:::drishti-risk", "arn:aws:s3tables:us-east-1:1111:bucket/x1",
                "arn:aws:s3tables:us-east-1:111122223333:bucket/Upper", "arn:aws:s3tables:us-east-1:111122223333:bucket/a/table/t"}) {
            assertThatThrownBy(() -> S3Tables.bucket(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("arn:aws:s3tables:<region>:<12-digit account>:bucket/<name>");
        }
    }

    @Test
    void theSettingsFollowFromTheArnAndWhatIsGivenWins() {
        Map<String, String> given = new HashMap<>(Map.of("catalog", "s3tables", "table-bucket-arn", ARN, "domain", "risk-results"));
        Map<String, String> s = S3Tables.expand(given);
        assertThat(s).containsEntry("catalog", "s3tables").containsEntry("uri", "https://s3tables.us-east-1.amazonaws.com/iceberg")
                .containsEntry("warehouse", ARN).containsEntry("prefix", ENCODED).containsEntry("catalog.rest.sigv4-enabled", "true")
                .containsEntry("catalog.rest.signing-name", "s3tables").containsEntry("catalog.rest.signing-region", "us-east-1")
                .containsEntry("s3.region", "us-east-1").containsEntry("vended-credentials", "false").containsEntry("namespace", "risk_results");
        assertThat(S3Tables.expand(s)).isEqualTo(s);                                               // twice is once
        given.put("uri", "https://vpce-0abc.s3tables.us-east-1.vpce.amazonaws.com/iceberg");     // a VPC endpoint
        given.put("catalog.rest.sigv4-enabled", "false");
        given.put("namespace", "risk");
        assertThat(S3Tables.expand(given)).containsEntry("uri", "https://vpce-0abc.s3tables.us-east-1.vpce.amazonaws.com/iceberg")
                .containsEntry("catalog.rest.sigv4-enabled", "true").containsEntry("namespace", "risk");
        assertThat(S3Tables.expand(Map.of("catalog", "s3tables", "warehouse", ARN))).containsEntry("table-bucket-arn", ARN);
        assertThatThrownBy(() -> S3Tables.expand(Map.of("catalog", "s3tables"))).hasMessageContaining("needs table-bucket-arn");
        assertThatThrownBy(() -> S3Tables.expand(Map.of("catalog", "s3tables", "table-bucket-arn", ARN, "warehouse",
                "arn:aws:s3tables:us-east-1:111122223333:bucket/other"))).hasMessageContaining("different table buckets");
    }

    @Test
    void kindsAndDomainsBecomeS3TablesNames() {
        assertThat(S3Tables.name("pnl-vector")).isEqualTo("pnl_vector");
        assertThat(S3Tables.name("Risk.Desk")).isEqualTo("risk_desk");
        assertThat(S3Tables.name("var_limit")).isEqualTo("var_limit");
        for (String bad : new String[] {"", "-x", "x-", "a b", "é"}) {
            assertThatThrownBy(() -> S3Tables.name(bad)).as(bad).hasMessageContaining("lower-case letters, digits and underscores");
        }
        assertThat(S3Tables.named(Map.of("catalog", "S3Tables"))).isTrue();
        assertThat(S3Tables.named(Map.of("catalog", "rest"))).isFalse();
    }
}
