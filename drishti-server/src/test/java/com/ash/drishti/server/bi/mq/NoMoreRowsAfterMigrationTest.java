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
package com.ash.drishti.server.bi.mq;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.Layered;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.QueryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guard of the owner's decision of 2026-11-07 (HOW_IT_FITS.md §A28 8.5): nobody sees more data after the upgrade. Every shipped sample in the
 * form it had before the table layer ({@code src/test/resources/bi/before-tables}) is migrated as {@code tables migrate} does, and every
 * case of {@link BiFoundationCases} is asked of the migrated models as {@code rita} (risk), {@code hana} (a desk head with two desks) and
 * {@code ann} (an analyst): no answer may have more rows than the one recorded before the switch, and a question refused before is refused
 * now or answered with no rows.
 */
class NoMoreRowsAfterMigrationTest {

    static final Path BEFORE = Path.of("src", "test", "resources", "bi", "before-tables");
    /** The recorded persons the guard reads, by the demo user they are. */
    static final Map<String, String> WHO = Map.of("risk", "rita", "desk-head-two-desks", "hana", "analyst", "ann");

    @TempDir Path store;

    @Test
    @SuppressWarnings("unchecked")
    void noRoleSeesMoreRowsAfterTheMigrationThanBefore() throws Exception {
        Map<String, String> files = new TreeMap<>();
        for (String folder : List.of("datasets", "models")) {
            try (Stream<Path> s = Files.list(BEFORE.resolve(folder))) {
                for (Path f : s.sorted().toList()) {
                    files.put(folder + "/" + f.getFileName(), Files.readString(f));
                }
            }
        }
        Map<String, String> written = Layered.migrate(files);
        String[] tables = written.entrySet().stream().filter(e -> e.getKey().endsWith(".table.yaml")).map(Map.Entry::getValue).toArray(String[]::new);
        List<String> models = written.entrySet().stream().filter(e -> e.getKey().endsWith(".model.yaml")).map(Map.Entry::getValue).toList();
        assertThat(models).as("the shipped models migrated").hasSizeGreaterThanOrEqualTo(10);

        BiFoundationCases.Shipped shipped = BiFoundationCases.shipped();
        MqService mq = new MqService(MqProperties.defaults());
        List<String> more = new ArrayList<>();
        int compared = 0;
        try (QueryService service = BiFoundationCases.service(store.resolve("snap"), shipped.lake());
                QueryService live = BiFoundationCases.liveService(store.resolve("live"), shipped.lake())) {
            for (String y : models) {
                ModelView v = MqHarness.model(y, k -> Optional.empty(), tables);
                String name = v.def().name();
                Path recorded = BiFoundationCases.FIXTURES.resolve("model-" + name + ".json");
                assertThat(recorded).as("a recorded answer for " + name).exists();
                Map<String, Object> before = BiFoundationCases.read("model-" + name + ".json");
                QueryService on = v.flat().tables().values().stream().anyMatch(Dataset.Table::isLive) ? live : service;
                Map<String, Object> after = BiFoundationCases.model(on, v, shipped.lake(), mq);
                for (String person : WHO.keySet()) {
                    Map<String, Object> was = (Map<String, Object>) before.get(person);
                    Map<String, Object> now = (Map<String, Object>) after.get(person);
                    for (Map.Entry<String, Object> c : was.entrySet()) {
                        Map<String, Object> a = (Map<String, Object>) c.getValue();
                        Map<String, Object> b = (Map<String, Object>) now.get(c.getKey());
                        if (b == null) {
                            continue;                                                   // a case not asked now cannot show more
                        }
                        compared++;
                        int n = rows(b);
                        if (a.containsKey("refused") ? n > 0 : n > rows(a)) {
                            more.add(name + " as " + WHO.get(person) + " (" + person + "), " + c.getKey() + ": " + (a.containsKey("refused") ? "refused" : rows(a))
                                    + " rows before, " + n + " now");
                        }
                    }
                }
            }
        }
        assertThat(more).as(compared + " answers compared").isEmpty();
        assertThat(compared).isGreaterThan(1000);
    }

    /** The rows an answer shows: its rows, or a rows extract's count; none when refused. */
    @SuppressWarnings("unchecked")
    static int rows(Map<String, Object> answer) {
        if (answer.containsKey("refused")) {
            return 0;
        }
        if (answer.get("count") instanceof Number n) {
            return n.intValue();
        }
        return answer.get("rows") instanceof List<?> l ? l.size() : 0;
    }
}
