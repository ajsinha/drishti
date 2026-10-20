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

import com.ash.drishti.iceberg.meta.TableMetadata;
import com.ash.drishti.iceberg.meta.TableUpdate;
import com.ash.drishti.iceberg.types.UnsupportedFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Metadata round trip: every fixture metadata file (pyiceberg's, v1 and v2) read and written back is the same JSON;
 * fields this engine does not know survive a commit's update; what it does not read is refused by name.
 */
class MetadataRoundTripTest {

    static List<Path> metadataFiles() throws Exception {
        try (Stream<Path> s = Files.walk(Path.of("src/test/resources/iceberg-fixtures"))) {
            return s.filter(p -> p.getFileName().toString().endsWith(".metadata.json")).sorted().toList();
        }
    }

    @Test
    void everyMetadataFileReadsAndWritesBackUnchanged() throws Exception {
        List<Path> files = metadataFiles();
        assertThat(files).hasSizeGreaterThan(20);
        for (Path f : files) {
            byte[] bytes = Files.readAllBytes(f);
            TableMetadata md = TableMetadata.read(bytes);
            assertThat(Fixtures.JSON.readTree(md.toBytes(false))).as(f.toString()).isEqualTo(Fixtures.JSON.readTree(bytes));
            assertThat(TableMetadata.read(md.toBytes(true)).json()).isEqualTo(md.json());          // gzip too
            assertThat(md.schema()).isNotNull();
            md.specs().forEach(s -> assertThat(s.partitionType(md.schema()).fields()).hasSize(s.fields().size()));
        }
    }

    @Test
    void unknownFieldsSurviveAnUpdate() throws Exception {
        Path f = metadataFiles().get(0);
        ObjectNode j = (ObjectNode) Fixtures.JSON.readTree(f.toFile());
        j.putObject("x-future-field").put("kept", true);
        ((ObjectNode) j.get("schemas").get(0)).put("x-schema-note", "kept too");
        TableMetadata md = new TableMetadata(j);
        TableMetadata next = new TableUpdate().setProperties(Map.of("drishti.note", "1")).apply(md, "file:/old.json", md.lastUpdatedMs() + 5);
        JsonNode out = Fixtures.JSON.readTree(next.toBytes(false));
        assertThat(out.path("x-future-field").path("kept").asBoolean()).isTrue();
        assertThat(out.path("schemas").get(0).path("x-schema-note").asText()).isEqualTo("kept too");
        assertThat(out.path("properties").path("drishti.note").asText()).isEqualTo("1");
        assertThat(out.path("metadata-log").get(out.path("metadata-log").size() - 1).path("metadata-file").asText()).isEqualTo("file:/old.json");
    }

    @Test
    void versionThreeEncryptionAndUnknownTypesAreRefusedByName() throws Exception {
        ObjectNode j = (ObjectNode) Fixtures.JSON.readTree(metadataFiles().get(0).toFile());
        ObjectNode v3 = j.deepCopy().put("format-version", 3);
        assertThatThrownBy(() -> new TableMetadata(v3)).isInstanceOf(UnsupportedFeature.class).hasMessageContaining("format version 3");
        ObjectNode enc = j.deepCopy();
        enc.putArray("encryption-keys").addObject().put("key-id", "k1");
        assertThatThrownBy(() -> new TableMetadata(enc)).isInstanceOf(UnsupportedFeature.class).hasMessageContaining("encrypted");
        ObjectNode odd = j.deepCopy();
        ((ObjectNode) odd.get("schemas").get(0).get("fields").get(0)).put("type", "variant");
        assertThatThrownBy(() -> new TableMetadata(odd)).isInstanceOf(UnsupportedFeature.class).hasMessageContaining("'variant'")
                .hasMessageContaining("format version 3").hasMessageContaining("engine: library");
    }
}
