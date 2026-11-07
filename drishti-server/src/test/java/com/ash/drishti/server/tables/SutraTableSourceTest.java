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
package com.ash.drishti.server.tables;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.ViewerContext;
import com.ash.drishti.engine.view.ViewModel;
import com.ash.drishti.rachana.model.Sutra;
import com.ash.drishti.rachana.parse.SutraParser;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A Sutra panel whose source is a virtual table (VIRTUAL_TABLES.md V13, {@code source: { table, where }}), opened by two people with
 * different row rules and masks on the same page, one after the other and at once: each gets their own rows and masks (multi-user
 * isolation through the {@link ViewerContext} passed with the call); a page built for nobody in particular reads no table; {@code where}
 * keeps rows with the page's context.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.rachana.hot-reload=false",
        "drishti.security.enabled=true", "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long",
        "drishti.security.redact[0]=dv01",
        "drishti.security.roles.fut-desk.kinds[0]=trade",
        "drishti.security.roles.all-desks.kinds[0]=trade", "drishti.security.roles.all-desks.raw=true",
        "drishti.database.sqlite.file=${drishti.test.dir}/identity-${random.uuid}/identity.db"})
class SutraTableSourceTest {

    static final Path ROOT;

    static {
        try {
            ROOT = Files.createTempDirectory("drishti-sutra-tables-test");
            Files.createDirectories(ROOT.resolve("trading"));
            Files.writeString(ROOT.resolve("trading/trade_book.table.yaml"), """
                    rachana: 3
                    table: trading.trade_book
                    from: { kind: trade }
                    columns:
                      PRODUCT: { path: $.productType, type: string }
                      MTM:     { path: $.mtm, type: decimal, masked: true }
                    rules:
                      - { role: fut-desk, where: "$.productType == 'FUT'" }
                      - { role: all-desks, where: "true" }
                    """);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void clean() throws java.io.IOException {
        try (java.util.stream.Stream<Path> walk = Files.walk(ROOT)) {
            for (Path f : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(f);
            }
        }
    }

    @DynamicPropertySource
    static void tables(DynamicPropertyRegistry r) {
        r.add("drishti.tables.root", ROOT::toString);
    }

    static final Sutra SUTRA = new SutraParser().parse("""
            rachana: 1
            sutra: book-page
            version: 1
            match: { kind: trade }
            title: { pill: Book, id: $.TRADE_ID }
            panels:
              - id: book
                kind: table
                title: The book
                source: { table: trading.trade_book, limit: 200 }
                rows: $src
                columns:
                  - { label: Product, bind: "@.PRODUCT" }
                  - { label: MTM, bind: "@.MTM" }
              - id: futures
                kind: table
                title: Futures
                source: { table: trading.trade_book, where: "@.PRODUCT == $.KEEP", limit: 200 }
                rows: $src
                columns:
                  - { label: Product, bind: "@.PRODUCT" }
            """, "book-page.sutra.yaml", "test");

    @Autowired ViewPipeline pipeline;
    @Autowired Entitlements entitlements;
    @Autowired JsonCodec codec;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    ViewModel page(ViewerContext who) {
        EntityDocument doc = new EntityDocument(EntityRef.of("trade", "T-1"), codec.read("{\"TRADE_ID\":\"T-1\",\"KEEP\":\"FUT\"}"),
                new Provenance("test", 1, Instant.now(), false));
        return pipeline.preview(Optional.of(SUTRA), doc, who);
    }

    /** The texts of one column of a table panel, or null when the panel says why it shows nothing. */
    List<String> column(ViewModel v, String panel, int col) {
        ViewModel.PanelView p = v.panels().stream().filter(x -> x.id().equals(panel)).findFirst().orElseThrow();
        if (p.denied() != null || p.data() == null) {
            return null;
        }
        JsonNode rows = json.valueToTree(p.data()).path("rows");
        List<String> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.path("cells").path(col).path("text").asText(r.path("cells").path(col).path("value").asText())));
        return out;
    }

    ViewerContext viewer(String role) {
        return entitlements.viewer(new Principal("u-" + role, List.of(role)));
    }

    @Test
    void twoPeopleOnTheSamePageGetTheirOwnRowsAndMasksInTurnAndAtOnce() throws Exception {
        List<String> fut = column(page(viewer("fut-desk")), "book", 0);
        List<String> all = column(page(viewer("all-desks")), "book", 0);
        assertThat(fut).isNotEmpty().allMatch("FUT"::equals);
        assertThat(all).hasSizeGreaterThan(fut.size()).anyMatch(x -> !"FUT".equals(x));
        assertThat(column(page(viewer("fut-desk")), "book", 1)).allMatch(DataNode.MASK::equals);
        assertThat(column(page(viewer("all-desks")), "book", 1)).anyMatch(x -> !x.isBlank() && !DataNode.MASK.equals(x));

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                boolean futDesk = i % 2 == 0;
                calls.add(() -> {
                    ViewModel v = page(viewer(futDesk ? "fut-desk" : "all-desks"));
                    List<String> products = column(v, "book", 0);
                    List<String> mtm = column(v, "book", 1);
                    return futDesk ? products.equals(fut) && mtm.stream().allMatch(DataNode.MASK::equals)
                            : products.equals(all) && mtm.stream().anyMatch(x -> !DataNode.MASK.equals(x));
                });
            }
            for (Future<Boolean> f : pool.invokeAll(calls)) {
                assertThat(f.get()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void whereKeepsRowsWithThePagesContextAndNobodyReadsNoTable() {
        List<String> kept = column(page(viewer("all-desks")), "futures", 0);
        assertThat(kept).isNotEmpty().allMatch("FUT"::equals);
        ViewModel nobody = page(ViewerContext.of(java.util.function.UnaryOperator.identity(), k -> true));
        assertThat(nobody.panels().stream().filter(p -> p.id().equals("book")).findFirst().orElseThrow().denied())
                .contains("signed-in person");
    }
}
