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
package com.ash.drishti.bicompute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.ash.drishti.bicompute.cache.StateCache;
import com.ash.drishti.kernel.api.pivot.AggregationPlan.FunctionCall;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.kernel.api.pivot.PivotView;
import com.ash.drishti.bicompute.custom.CustomFunctions;
import com.ash.drishti.bicompute.custom.FunctionDef;
import com.ash.drishti.bicompute.custom.FunctionStore;
import com.ash.drishti.bicompute.custom.OfficialRun;
import com.ash.drishti.bicompute.custom.SqlMacroFunction;
import com.ash.drishti.bicompute.functions.Functions;
import com.ash.drishti.bicompute.harness.FunctionCheck;
import com.ash.drishti.kernel.api.pivot.AggFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.Kind;
import com.ash.drishti.kernel.api.pivot.Viewer;
import com.ash.drishti.common.DrishtiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Custom functions (HOW_IT_FITS.md section A26.5): saved only when the harness passes, versions, private until someone other than
 * the author approves them, withdrawn in the cell, never in an official run, a macro rewritten into states when it is a formula, and a
 * function without an SQL form fed in batches by the tabular provider with the same figures as SQL.
 */
class CustomFunctionsTest {

    final List<String> audit = new ArrayList<>();

    CustomFunctions registry(CustomFunctions.Settings s) {
        CustomFunctions.Access access = new CustomFunctions.Access() {
            public Set<String> groups(Viewer v) {
                return v.user().equals("raj") ? Set.of("rates") : Set.of();
            }

            public boolean approver(Viewer v) {
                return v.user().equals("anna") || v.user().equals("asha");
            }

            public boolean admin(Viewer v) {
                return v.user().equals("admin");
            }
        };
        return new CustomFunctions(FunctionStore.inMemory(), access, (u, a, t, d) -> audit.add(u + " " + a + " " + t), s, null, null, null);
    }

    static Map<String, Object> sample() {
        List<Map<String, Object>> rows = new ArrayList<>();
        java.util.Random r = new java.util.Random(5);
        for (int i = 0; i < 2_000; i++) {
            rows.add(Map.of("r", r.nextGaussian() * 0.01, "w", 1 + r.nextInt(100)));
        }
        return Map.of("rows", rows);
    }

    static Map<String, Object> body(String name, String code) {
        return Map.of("name", name, "language", "formula", "code", code, "inputs", List.of("r", "w"));
    }

    @Test
    void savedOnlyAfterTheHarnessThenVersionsApprovalAndWithdrawal() {
        CustomFunctions fns = registry(CustomFunctions.Settings.defaults());
        Viewer asha = Viewer.of("asha");
        Batch sample = CustomFunctions.sample(List.of(new FunctionDef.Input("r", "number"), new FunctionDef.Input("w", "number")),
                sample().get("rows"));
        FunctionDef v1 = fns.save(asha, body("wret", "sum(r * w) / sum(w)"), sample);
        assertThat(v1.ref()).isEqualTo("fn:asha/wret@1");
        assertThat(v1.kind()).isEqualTo(Kind.MERGEABLE);
        FunctionDef v2 = fns.save(asha, body("wret", "sum(r * w) / sum(abs(w))"), sample);
        assertThat(v2.version()).isEqualTo(2);
        assertThatThrownBy(() -> fns.save(asha, body("var", "sum(r)"), sample)).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("official measure");

        // private: raj may not use it; the author may not approve it; anna (an approver) approves it for the rates group
        assertThatThrownBy(() -> fns.resolve(Viewer.of("raj"), "fn:asha/wret@1(PNL, NOTIONAL)")).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> fns.approve(asha, "asha", "wret", 1, "everyone", "fine")).hasMessageContaining("never approves their own");
        assertThatThrownBy(() -> fns.approve(Viewer.of("raj"), "asha", "wret", 1, "everyone", "fine")).hasMessageContaining("may not approve");
        fns.approve(Viewer.of("anna"), "asha", "wret", 1, "group:rates", "reviewed: a weighted mean");
        CustomFunctions.Resolved r = fns.resolve(Viewer.of("raj"), "fn:asha/wret@1(PNL, NOTIONAL)");
        assertThat(r.id()).isEqualTo("fn:asha/wret@1(PNL, NOTIONAL)");
        assertThat(r.fields()).containsExactly("PNL", "NOTIONAL");
        assertThatThrownBy(() -> fns.resolve(Viewer.of("bob"), "fn:asha/wret@1(PNL, NOTIONAL)")).isInstanceOf(DrishtiException.class);
        assertThat(fns.list(Viewer.of("raj"))).hasSize(1);

        // withdrawn: the cell says so, no figure; official runs never resolve
        fns.revoke(Viewer.of("anna"), "asha", "wret", 1, "superseded by @2");
        CustomFunctions.Resolved w = fns.resolve(Viewer.of("raj"), "fn:asha/wret@1(PNL, NOTIONAL)");
        assertThat(w.fn().finish(w.fn().start())).isNull();
        assertThat(w.fn().emptyNote()).contains("function withdrawn by anna").contains("superseded");
        assertThatThrownBy(() -> OfficialRun.during("the VaR sign-off", () -> fns.resolve(asha, "fn:asha/wret@2(PNL, NOTIONAL)")))
                .hasMessageContaining("never run in the VaR sign-off");
        assertThat(audit).anyMatch(a -> a.startsWith("anna pivot.function.approve fn:asha/wret@1"))
                .anyMatch(a -> a.startsWith("anna pivot.function.revoke")).anyMatch(a -> a.startsWith("raj pivot.function.use"));
    }

    @Test
    void aWrongMergeIsNeverSaved() {
        AggFunction<Object> sum = Functions.builtIn("sum", List.of("x"), List.of()).orElseThrow();
        AggFunction<Object> wrong = new FunctionsTestWrong(sum);
        FunctionCheck.Verdict v = FunctionCheck.run(wrong, FunctionsTest.rows(5_000, 9), FunctionCheck.Options.defaults());
        assertThat(v.passed()).isFalse();
        assertThat(v.firstFailure()).startsWith("batches and splits");
        FunctionCheck.Verdict bound = FunctionCheck.run(sum, Batch.of(new double[] {1, 2, 3}), new FunctionCheck.Options(1e-12, 2, 0, 8, 0));
        assertThat(bound.passed()).as("a state over its declared bound").isFalse();
    }

    @Test
    void aMacroThatIsAFormulaIsMergeableAndAnotherIsHolistic() {
        AggFunction<Object> f = SqlMacroFunction.of("fn:a/w@1", "CREATE MACRO w(r, x) AS sum(r * x) / sum(x)", null, null);
        assertThat(f.kind()).isEqualTo(Kind.MERGEABLE);
        AggFunction<Object> slope = SqlMacroFunction.of("fn:a/s@1", "CREATE MACRO s(y, x) AS regr_slope(y, x)", null, null);
        assertThat(slope.kind()).isEqualTo(Kind.HOLISTIC);
        assertThat(slope.sqlState(List.of("\"i0\"", "\"i1\""))).containsExactly("regr_slope((\"i0\"), (\"i1\"))");
        assertThatThrownBy(() -> SqlMacroFunction.parse("CREATE MACRO s(y) AS (SELECT sum(y) FROM secrets)")).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> SqlMacroFunction.parse("CREATE MACRO s(y) AS sum(y); DROP TABLE t")).hasMessageContaining("one expression");
        assertThatThrownBy(() -> SqlMacroFunction.parse("CREATE MACRO s(y) AS read_csv('x')")).hasMessageContaining("not allowed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFunctionWithoutSqlIsFedInBatchesAndEqualsSql() {
        try (PivotFixture fx = new PivotFixture(3_000, StateCache.none(), PivotCore.Settings.defaults())) {
            fx.provider.feed(97, 5_000_000L);
            AggFunction<Object> sum = Functions.builtIn("sum", List.of("x"), List.of()).orElseThrow();
            fx.core.functions((v, text, label) -> text.startsWith("fn:java/sum") ? Optional.of(new FunctionCall(text, label == null ? text : label,
                    new NoSql(sum), List.of("NOTIONAL"))) : Optional.empty());
            Map<String, Object> a = fx.core.query(Viewer.of("rita"), PivotView.parse(Map.of("source", "trades", "rows", List.of("REGION", "DESK"),
                    "expandTo", Map.of("rows", 2), "values", List.of(Map.of("measure", "sum(NOTIONAL)", "as", "S"),
                            Map.of("measure", "fn:java/sum@1(NOTIONAL)", "as", "J")))));
            Map<String, Map<String, Object>> cells = (Map<String, Map<String, Object>>) a.get("cells");
            assertThat(cells).hasSizeGreaterThan(10);
            for (Map<String, Object> c : cells.values()) {
                Map<String, Object> v = (Map<String, Object>) c.get("values");
                assertThat(((Number) v.get("J")).doubleValue()).isCloseTo(((Number) v.get("S")).doubleValue(), offset(1e-6));
            }
        }
    }

    /** Sum with no SQL form (as a Java plug-in or a Python function is): the tabular provider must feed it rows. */
    static final class NoSql implements AggFunction<Object> {
        private final AggFunction<Object> f;

        NoSql(AggFunction<Object> f) {
            this.f = f;
        }

        public String name() { return "fn:java/sum@1"; }
        public Kind kind() { return f.kind(); }
        public int inputs() { return 1; }
        public Object start() { return f.start(); }
        public Object add(Object s, Batch b) { return f.add(s, b); }
        public Object merge(Object a, Object b) { return f.merge(a, b); }
        public Double finish(Object s) { return f.finish(s); }
        public byte[] encode(Object s) { return f.encode(s); }
        public Object decode(byte[] b) { return f.decode(b); }
        public long bytes(Object s) { return f.bytes(s); }
        public List<String> sqlState(List<String> in) { return List.of(); }
        public Object fromSql(Object[] p) { throw new UnsupportedOperationException(); }
    }

    /** A merge that forgets its second side. */
    static final class FunctionsTestWrong implements AggFunction<Object> {
        private final AggFunction<Object> f;

        FunctionsTestWrong(AggFunction<Object> f) {
            this.f = f;
        }

        public String name() { return "bad_sum"; }
        public Kind kind() { return Kind.MERGEABLE; }
        public int inputs() { return 1; }
        public Object start() { return f.start(); }
        public Object add(Object s, Batch b) { return f.add(s, b); }
        public Object merge(Object a, Object b) { return a; }
        public Double finish(Object s) { return f.finish(s); }
        public byte[] encode(Object s) { return f.encode(s); }
        public Object decode(byte[] b) { return f.decode(b); }
        public long bytes(Object s) { return 40; }
        public List<String> sqlState(List<String> in) { return List.of(); }
        public Object fromSql(Object[] p) { return null; }
    }
}
