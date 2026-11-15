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

import java.util.regex.Matcher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The table folder a missing file names is cut at the last data/ or metadata/ folder: a lake under any folder named data reads whole. */
class TableProblemsPathTest {

    @ParameterizedTest
    @CsvSource({
        "/data/lake/desk/trade/metadata/snap-1.avro, /data/lake/desk/trade",
        "/var/lib/drishti/data/iceberg/desk/trade/data/date=2026-09-30/f.parquet, /var/lib/drishti/data/iceberg/desk/trade",
        "/home/me/drishti/data/tmp/t/target/tmp/written-1/desk/trade/metadata/v1.metadata.json, /home/me/drishti/data/tmp/t/target/tmp/written-1/desk/trade",
        "file:/srv/data/desk/trade/data/f.parquet, file:/srv/data/desk/trade",
        "s3://bucket/data/wh/desk/trade/metadata/snap-2.avro, s3://bucket/data/wh/desk/trade"})
    void theTableFolderIsBeforeTheLastDataOrMetadataFolder(String file, String table) {
        Matcher m = TableProblems.PATH.matcher("File does not exist: " + file);
        assertThat(m.find()).isTrue();
        assertThat(m.group(1)).isEqualTo(table);
    }
}
