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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.VectorReads;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.var.VarBook;
import com.ash.drishti.server.PinnedBusinessDate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * A date's first open with its vector files decoded several at once ({@code drishti.var.read}, VAR_GUIDE.md section 27a.6) builds the
 * very book one thread builds: the same trades in the same order with every vector to the bit, the same positions and their vectors, the
 * same rejected rows and every node's aggregate to the bit; a damaged vector file fails the load as it does on one thread.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.packs.enabled=finance",
        "drishti.sources.plugins.demo.enabled=false", "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors-dir=target/var-parallel-read/connectors",
        "drishti.sources.connectors.var-positions.plugin=var-positions", "drishti.sources.connectors.var-positions.kinds[0]=position",
        "drishti.sources.routes.pnl-vector=risk-lake", "drishti.sources.routes.position=var-positions",
        "drishti.sources.routes.scenario-set=risk-lake", "drishti.sources.routes.risk-hierarchy=risk-lake",
        "drishti.sources.routes.portfolio=risk-lake", "drishti.sources.routes.var-limit=risk-lake", "drishti.sources.routes.scenario-pv=risk-lake",
        "drishti.sources.routes.fx-scenario=risk-lake", "drishti.sources.routes.fx-spot=risk-lake",
        "drishti.var.positions.rules[0].name=securities", "drishti.var.positions.rules[0].groups[0]=Fixed income",
        "drishti.var.positions.rules[0].key[0]=SECURITY_ID",
        "drishti.var.official.on-start=false", "drishti.var.official.enabled=false",
        "drishti.var.official.root=target/var-parallel-read/official",
        "drishti.var.threads=2", "drishti.var.tier.mode=heap", "drishti.var.currencies[0]=USD",
        "drishti.var.read.threads=4", "drishti.var.read.buffer-mb=1",
        "drishti.identity.database-url=jdbc:sqlite:target/identity-${random.uuid}/identity.db"})
@Import(PinnedBusinessDate.class)
class VarParallelReadTest {

    static final Path BASE = Path.of("target", "var-parallel-read").toAbsolutePath();
    static final Path LAKE = BASE.resolve("lake");
    static final LocalDate DAY = VarLakeFixture.DAY;
    /** The fixture's P&amp;L file and this many more, appended: a date of several vector files. */
    static final int MORE_FILES = 4;
    static final int MORE_TRADES = 400;
    /** The promoted columns of the fixture's P&amp;L table (an append keeps its schema). */
    static final List<String> PROMOTED = List.of("TRADE_ID", "LEGAL_ENTITY", "DIVISION", "DESK", "TT_BOOK", "PORTFOLIO", "PRODUCT_GROUP",
            "PRODUCT_TYPE", "SECURITY_ID", "CURRENCY", "COUNTERPARTY", "DIRECTION", "QUANTITY", "PV", "TRADE_STATUS", "MATURITY_DATE",
            "STATUS", "REPORTING_CCY", "SCENARIO_SET_ID", "BATCH_ID");

    static {
        try {
            if (Files.exists(BASE)) {
                try (Stream<Path> s = Files.walk(BASE)) {
                    for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            VarLakeFixture.write(LAKE);
            Path work = Files.createDirectories(LAKE.resolve("_work"));
            Random r = new Random(5);
            for (int f = 0; f < MORE_FILES; f++) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (int i = 0; i < MORE_TRADES; i++) {
                    rows.add(trade("TX-" + f + "-" + i, i, r));
                }
                VarLakeFixture.table(LAKE, work, "pnl-vector", "VECTOR_ID", rows, PROMOTED, "PNL", "append");
            }
            Path conn = Files.createDirectories(BASE.resolve("connectors"));
            Files.writeString(conn.resolve("risk-lake.yaml"), VarShardClusterTest.riskLake("delta", LAKE, BASE));
        } catch (Exception e) {
            throw new IllegalStateException("the lake cannot be written: " + e, e);
        }
    }

    /** An OTC trade of the fixture's morning run, in one of its books, with a vector of its own (fractions: every bit counts). */
    static Map<String, Object> trade(String id, int i, Random r) {
        String book = "BOOK-" + (i % 3 + 1);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("VECTOR_ID", "PV-" + id);
        d.put("TRADE_ID", id);
        d.put("BUSINESS_DATE", DAY.toString());
        d.put("SCENARIO_SET_ID", "HS-20");
        d.put("BATCH_ID", "B-" + DAY + "-01");
        d.put("LEGAL_ENTITY", book.equals("BOOK-3") ? "LE-B" : "LE-A");
        d.put("DIVISION", "DIV-M");
        d.put("DESK", book.equals("BOOK-3") ? "DESK-Y" : "DESK-X");
        d.put("TT_BOOK", book);
        d.put("PORTFOLIO", book);
        d.put("PRODUCT_GROUP", "Rates");
        d.put("PRODUCT_TYPE", "IRS");
        d.put("SECURITY_ID", null);
        d.put("CURRENCY", "USD");
        d.put("COUNTERPARTY", "CP-" + (i % 7));
        d.put("DIRECTION", "LONG");
        d.put("QUANTITY", 1.0);
        d.put("PV", 1000.0);
        d.put("TRADE_STATUS", "VALIDATED");
        d.put("MATURITY_DATE", "2030-01-15");
        d.put("STATUS", "ok");
        d.put("REPORTING_CCY", "USD");
        double[] v = new double[VarLakeFixture.N];
        for (int j = 0; j < v.length; j++) {
            v[j] = r.nextGaussian() * 1000.0 / 3.0;
        }
        d.put("PNL", v);
        return d;
    }

    @Autowired VarService svc;

    @Test
    void severalFilesAtOnceBuildTheBookOneThreadBuildsAndADamagedFileFailsTheLoad() throws Exception {
        VarLoader loader = svc.loader();
        VarBook one = loader.reads(VectorReads.Options.serial()).load(DAY).morning().book();
        VarBook many = loader.reads(new VectorReads.Options(4, 1L << 20)).load(DAY).morning().book();
        assertThat(one.tradeCount()).isEqualTo(MORE_FILES * MORE_TRADES + 6);   // the fixture's six counted trades and the appended ones
        assertSame(one, many);
        VarBook defaults = loader.reads(VarReadProperties.defaults().options()).load(DAY).morning().book();
        assertSame(one, defaults);

        // a damaged vector file: the load fails as on one thread, said as the source's failure
        List<Path> files;
        try (Stream<Path> s = Files.walk(LAKE.resolve("risk/pnl-vector"))) {
            files = s.filter(p -> p.toString().endsWith(".parquet")).sorted().toList();
        }
        assertThat(files).hasSizeGreaterThan(2);
        Files.write(files.get(1), "not a parquet file".getBytes(StandardCharsets.UTF_8));
        for (VectorReads.Options o : List.of(VectorReads.Options.serial(), new VectorReads.Options(4, 1L << 20))) {
            assertThatThrownBy(() -> loader.reads(o).load(DAY)).as(o.toString()).isInstanceOf(DrishtiException.class)
                    .satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.SOURCE_FAILED));
        }
        loader.reads(VarReadProperties.defaults().options());
    }

    /** The same book: trades, vectors, positions, rejected rows and every node's aggregate, to the bit and in order. */
    static void assertSame(VarBook a, VarBook b) {
        assertThat(b.tradeCount()).isEqualTo(a.tradeCount());
        assertThat(b.positionCount()).isEqualTo(a.positionCount());
        assertThat(b.failed()).isEqualTo(a.failed());
        for (int t = 0; t < a.tradeCount(); t++) {
            assertThat(b.tradeId(t)).as("trade " + t).isEqualTo(a.tradeId(t));
            assertBits(a.tradeVector(t), b.tradeVector(t), a.tradeId(t));
        }
        for (int p = 0; p < a.positionCount(); p++) {
            assertThat(b.positionId(p)).as("position " + p).isEqualTo(a.positionId(p));
            assertBits(a.positionVector(p), b.positionVector(p), a.positionId(p));
        }
        assertThat(b.nodes().keySet()).containsExactlyElementsOf(a.nodes().keySet());
        for (String n : a.nodes().keySet()) {
            assertBits(a.aggregate(n), b.aggregate(n), n);
        }
    }

    static void assertBits(double[] x, double[] y, String what) {
        assertThat(y).as(what).hasSameSizeAs(x);
        for (int i = 0; i < x.length; i++) {
            assertThat(Double.doubleToRawLongBits(y[i])).as(what + "[" + i + "]").isEqualTo(Double.doubleToRawLongBits(x[i]));
        }
    }
}
