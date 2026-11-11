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
package com.ash.drishti.server.pivot;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.bicompute.formula.FormulaLowering;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.identity.content.FileContentStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The migration of saved pivot formulas (PivotFormulaMigration): every formula of the parity set (drishti-bicompute's
 * {@code formula-parity/corpus.yaml}, whose answers PivotFormulaParityTest proves the same before and after) is migrated to exactly the text
 * the set says ({@code rachana}, or itself when it reads already), and the pivot reads the result; saved views, their versions and formula
 * functions in the content store are rewritten in place, audited, idempotently; what the pivot reads already is never touched.
 * {@code -Ddrishti.record.formulas=true} writes each formula's migrated text into the set (done once).
 */
class PivotFormulaMigrationTest {

    static final Path CORPUS = Path.of("..", "..", "compute", "drishti-bicompute", "src", "test", "resources", "formula-parity", "corpus.yaml");

    @Test
    @SuppressWarnings("unchecked")
    void everyFormulaOfTheParitySetMigratesToTheTextTheSetSays() throws Exception {
        Map<String, Object> corpus = new ObjectMapper(new YAMLFactory()).readValue(Files.readString(CORPUS), Map.class);
        List<String> lines = new ArrayList<>(Files.readAllLines(CORPUS));
        int rewritten = 0;
        for (Object o : (List<Object>) corpus.get("formulas")) {
            Map<String, Object> e = (Map<String, Object>) o;
            String formula = String.valueOf(e.get("formula"));
            String migrated = PivotFormulaMigration.migrate(formula).orElseThrow(() -> new AssertionError("not migrated: " + formula));
            assertThat(FormulaLowering.isFormula(migrated)).as(migrated).isTrue();
            if (Boolean.getBoolean("drishti.record.formulas")) {
                if (!migrated.equals(formula)) {
                    String q = new ObjectMapper().writeValueAsString(formula);
                    for (int i = 0; i < lines.size(); i++) {
                        if (lines.get(i).startsWith("- {formula: " + q + ",") && !lines.get(i).contains("rachana:")) {
                            lines.set(i, lines.get(i).substring(0, lines.get(i).length() - 1) + ", rachana: " + new ObjectMapper().writeValueAsString(migrated) + "}");
                        }
                    }
                }
                continue;
            }
            Object want = e.get("rachana");
            assertThat(migrated).as(formula).isEqualTo(want == null ? formula : String.valueOf(want));
            rewritten += migrated.equals(formula) ? 0 : 1;
        }
        if (Boolean.getBoolean("drishti.record.formulas")) {
            Files.write(CORPUS, lines);
            return;
        }
        assertThat(rewritten).as("the old syntax of the set is rewritten").isGreaterThan(10);
    }

    @Test
    void savedViewsVersionsAndFormulaFunctionsAreRewrittenInPlaceOnceAndAudited(@TempDir Path dir) throws Exception {
        ContentStore store = new FileContentStore(dir);
        put(store, "pivot/views/asha/desk.json", """
                {"name":"desk","view":{"source":"trade","values":[{"measure":"sum(PNL, where=DESK == 'FX' and PNL != None)","as":"FX"},
                {"measure":"count()","as":"N"},{"measure":"sum($.LEGS[*].NOTIONAL)","as":"L"},{"measure":"sum(PNL) if count() > 1 else 0","as":"P"}]},"version":2}""");
        put(store, "pivot/view-versions/asha/desk/000001.json", """
                {"name":"desk","view":{"source":"trade","values":[{"measure":"sum(PNL) ** 2","as":"S"}]},"version":1}""");
        put(store, "pivot/functions/asha/wret.json", """
                {"id":"asha/wret","versions":[{"name":"wret","version":1,"language":"formula","code":"sum(r * w, where=not r == None) / sum(w)"},
                {"name":"wret","version":2,"language":"sql","code":"CREATE MACRO wret(r, w) AS sum(r * w) / sum(w)"}]}""");
        put(store, "pivot/views/ben/plain.json", """
                {"name":"plain","view":{"source":"trade","values":[{"measure":"sum(PNL) / sum(abs(PNL))","as":"R"}]},"version":1}""");
        List<String> audit = new ArrayList<>();
        AuditLog log = new AuditLog() {
            @Override
            public void record(String actor, String action, String subject, String detail) {
                audit.add(action + " " + subject + ": " + detail);
            }

            @Override
            public List<Event> recent(int limit, String subject) {
                return List.of();
            }
        };
        String plainBefore = store.get("pivot/views/ben/plain.json").orElseThrow().version();
        PivotFormulaMigration m = new PivotFormulaMigration(store, log);
        assertThat(m.run(true)).hasSize(4);
        assertThat(audit).as("a dry run writes nothing").isEmpty();
        m.run(false);
        String view = text(store, "pivot/views/asha/desk.json");
        assertThat(view).contains("sum(PNL, where = DESK == 'FX' && PNL != null)").contains("count() > 1 ? sum(PNL) : 0").contains("\"count()\"")
                .contains("sum($.LEGS[*].NOTIONAL)");
        assertThat(text(store, "pivot/view-versions/asha/desk/000001.json")).contains("power(sum(PNL), 2)");
        String fn = text(store, "pivot/functions/asha/wret.json");
        assertThat(fn).contains("sum(r * w, where = !(r == null)) / sum(w)").contains("CREATE MACRO wret(r, w) AS sum(r * w) / sum(w)");
        assertThat(audit).hasSize(3).allMatch(a -> a.startsWith("pivot-formula-migrate pivot/"));
        assertThat(store.get("pivot/views/ben/plain.json").orElseThrow().version()).as("a view in Rachana already is not touched").isEqualTo(plainBefore);
        audit.clear();
        assertThat(m.run(false)).as("idempotent").isEmpty();
        assertThat(audit).isEmpty();
    }

    private static void put(ContentStore store, String key, String json) {
        store.put(key, json.replace("\n", "").getBytes(StandardCharsets.UTF_8), null);
    }

    private static String text(ContentStore store, String key) {
        return store.get(key).orElseThrow().text();
    }
}
