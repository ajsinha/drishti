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
package com.ash.drishti.iceberg.meta;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Refs as the Apache library keeps them: moving {@code main} keeps the branch's retention settings; removing snapshots removes the refs
 * that name them and their statistics files' entries; tags are refs ({@link TableMetadata#allRefs}).
 */
class TableUpdateRefsTest {

    static TableMetadata table() throws Exception {
        String json = """
                {"format-version":2,"table-uuid":"u","location":"file:/lake/t","last-sequence-number":3,"last-updated-ms":1,"last-column-id":1,
                 "current-schema-id":0,"schemas":[{"type":"struct","schema-id":0,"fields":[{"id":1,"name":"id","required":false,"type":"long"}]}],
                 "default-spec-id":0,"partition-specs":[{"spec-id":0,"fields":[]}],"last-partition-id":999,"default-sort-order-id":0,
                 "sort-orders":[{"order-id":0,"fields":[]}],"properties":{},"current-snapshot-id":3,
                 "snapshots":[{"snapshot-id":1,"sequence-number":1,"timestamp-ms":10,"manifest-list":"l1","summary":{"operation":"append"}},
                              {"snapshot-id":2,"sequence-number":2,"timestamp-ms":20,"manifest-list":"l2","summary":{"operation":"append"}},
                              {"snapshot-id":3,"sequence-number":3,"timestamp-ms":30,"manifest-list":"l3","summary":{"operation":"append"}}],
                 "refs":{"main":{"snapshot-id":3,"type":"branch","min-snapshots-to-keep":5,"max-snapshot-age-ms":86400000},
                         "eod":{"snapshot-id":1,"type":"tag","max-ref-age-ms":999},"audit":{"snapshot-id":2,"type":"branch"}},
                 "statistics":[{"snapshot-id":2,"statistics-path":"s2.puffin","file-size-in-bytes":1,"file-footer-size-in-bytes":1,"blob-metadata":[]},
                               {"snapshot-id":3,"statistics-path":"s3.puffin","file-size-in-bytes":1,"file-footer-size-in-bytes":1,"blob-metadata":[]}],
                 "snapshot-log":[{"timestamp-ms":10,"snapshot-id":1},{"timestamp-ms":20,"snapshot-id":2},{"timestamp-ms":30,"snapshot-id":3}],
                 "metadata-log":[]}""";
        return new TableMetadata((ObjectNode) new ObjectMapper().readTree(json));
    }

    @Test
    void tagsAreRefs() throws Exception {
        TableMetadata t = table();
        assertThat(t.allRefs()).containsEntry("eod", 1L).containsEntry("audit", 2L).containsEntry("main", 3L);
        assertThat(t.refs()).doesNotContainKey("eod");                      // branches only
    }

    @Test
    void movingMainKeepsItsRetention() throws Exception {
        TableMetadata next = new TableUpdate().setMain(2).apply(table(), null, 40);
        var main = next.json().path("refs").path("main");
        assertThat(main.path("snapshot-id").asLong()).isEqualTo(2);
        assertThat(main.path("type").asText()).isEqualTo("branch");
        assertThat(main.path("min-snapshots-to-keep").asInt()).isEqualTo(5);
        assertThat(main.path("max-snapshot-age-ms").asLong()).isEqualTo(86_400_000L);
        assertThat(next.json().path("refs").path("eod").path("type").asText()).isEqualTo("tag");
    }

    @Test
    void removingSnapshotsRemovesTheirRefsAndStatistics() throws Exception {
        TableMetadata next = new TableUpdate().removeSnapshots(Set.of(1L, 2L)).apply(table(), null, 40);
        assertThat(next.allRefs()).containsOnlyKeys("main");
        assertThat(next.json().path("statistics")).hasSize(1);
        assertThat(next.json().path("statistics").get(0).path("snapshot-id").asLong()).isEqualTo(3);
        assertThat(next.snapshots()).hasSize(1);
    }
}
