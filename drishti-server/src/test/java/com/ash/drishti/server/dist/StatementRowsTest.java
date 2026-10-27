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
package com.ash.drishti.server.dist;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * QA2-01: a statement's table holds every row up to {@code drishti.distribution.statements.max-rows}, never the 5,000 rows one screen's answer holds, and a
 * table longer than that says so above itself, in the preview's {@code cut} and in the Excel file. QA2-02: the tokens work in a text band and a visual's title
 * as in the header, and an unknown token is refused (DRS-9038).
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/stmt-rows-${random.uuid}/identity.db", "drishti.packs.overlay=${drishti.test.dir}/stmt-rows-overlay/added.yaml",
        "drishti.bi.dir=${drishti.test.dir}/stmt-rows-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=${drishti.test.dir}/stmt-rows-store/${random.uuid}",
        "drishti.packs.samples=developers", "drishti.sources.connectors-dir=${drishti.test.dir}/stmt-rows-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.rows-lake.plugin=file", "drishti.sources.connectors.rows-lake.kinds[0]=trade",
        "drishti.sources.connectors.rows-lake.settings.rescan-seconds=3600",
        "drishti.sources.connectors.rows-lake.settings.layout.trade.columns=TRADE_ID,DESK,NOTIONAL,TRADE_DATE",
        "drishti.distribution.statements.max-rows=6000", "drishti.distribution.statements.max-pages=2000", "drishti.distribution.export.timeout=120s"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatementRowsTest {

    static final String DAY = "2026-09-30";
    static final int TRADES = 6_500;
    static final Pattern ID = Pattern.compile("^2\\d{5}$");
    static Path root;

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    static String id(int i) {
        return String.valueOf(200_000 + i);
    }

    /** One trade in five is on DESK-X: the "most" table leaves those out and holds 5,200 rows. */
    static String desk(int i) {
        return i % 5 == 0 ? "DESK-X" : i % 2 == 0 ? "DESK-A" : "DESK-B";
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        root = com.ash.drishti.server.TestRun.temp("drishti-stmt-rows");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < TRADES; i++) {
            lines.add("{\"id\":\"" + id(i) + "\",\"TRADE_ID\":\"" + id(i) + "\",\"DESK\":\"" + desk(i) + "\",\"NOTIONAL\":" + (1_000.0 + i) + ",\"TRADE_DATE\":\"" + DAY + "\"}");
        }
        Path f = root.resolve("lake").resolve(DAY).resolve("trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
        r.add("drishti.sources.connectors.rows-lake.settings.root", () -> root.resolve("lake").toString());
    }

    static final String STATEMENT = """
            report: rows-statement
            title: Every row
            model: rows-model@1
            statement:
              paper: A4
              header: { left: "{title}", right: "Page {page} of {pages}" }
              period: none
              fontSize: 7
            pages:
              - id: statement
                title: Statement
                layout: statement
                visuals:
                  - { id: count, type: card, title: Trades, at: { x: 0, y: 0, w: 4, h: 2 }, wells: { values: [Trades] } }
                  - { id: most, type: table, title: Most, at: { x: 0, y: 2, w: 12, h: 8 }, wells: { columns: [trade.TRADE_ID, trade.DESK] },
                      sort: { by: trade.TRADE_ID }, filters: [{ field: trade.DESK, op: ne, values: [DESK-X] }] }
                  - { id: all, type: table, title: All, at: { x: 0, y: 10, w: 12, h: 8 }, wells: { columns: [trade.TRADE_ID, trade.DESK, NOTIONAL] },
                      sort: { by: trade.TRADE_ID }, format: { pageBreak: before } }
            """;

    static {
        try {
            Path folder = com.ash.drishti.server.TestRun.root().resolve("stmt-rows-folder");
            Path datasets = Files.createDirectories(folder.resolve("datasets"));
            Path models = Files.createDirectories(folder.resolve("models"));
            Path reports = Files.createDirectories(folder.resolve("reports"));
            Files.writeString(datasets.resolve("rows-trade.dataset.yaml"), """
                    rachana: 2
                    dataset: rows-trade
                    version: 1.0.0
                    source: { connector: rows-lake, kind: trade }
                    grain: { keys: [id] }
                    columns:
                      TRADE_ID: { type: string }
                      DESK:     { type: string }
                      NOTIONAL: { type: decimal, aggregate: sum }
                    """);
            Files.writeString(models.resolve("rows-model.model.yaml"), """
                    rachana: 2
                    model: rows-model
                    version: 1.0.0
                    datasets:
                      trade: { dataset: rows-trade, role: fact }
                    measures:
                      NOTIONAL: { expr: "sum(trade.NOTIONAL)" }
                      Trades:   { expr: "count(trade)" }
                    """);
            Files.writeString(reports.resolve("rows-statement.report.yaml"), STATEMENT);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @AfterAll
    void cleanUp() throws Exception {
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    String admin() {
        return "Bearer " + tokens.mint("drishti-dev-admin", List.of("admin"), 300);
    }

    MvcResult call(String path, Object body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(java.net.URI.create(path)).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn();
    }

    JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 299);
        return json.readTree(r.getResponse().getContentAsString());
    }

    /** The trade ids drawn under each table's title, in page order: the ids that follow the title "Most" until "All". */
    static Map<String, List<String>> idsByTable(JsonNode preview) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        String table = null;
        for (JsonNode page : preview.path("pages")) {
            for (JsonNode op : page.path("ops")) {
                String text = op.path("text").asText("");
                if ("title".equals(op.path("role").asText()) && (text.startsWith("Most") || text.startsWith("All"))) {
                    table = text.startsWith("Most") ? "most" : "all";
                } else if (table != null && ID.matcher(text).matches()) {
                    out.computeIfAbsent(table, k -> new ArrayList<>()).add(text);
                }
            }
        }
        return out;
    }

    @Test
    void aStatementTablePrintsEveryRowPastTheScreensFiveThousandAndSaysWhereItStops() throws Exception {
        JsonNode preview = ok(call("/api/v1/bi/reports/statement/preview", Map.of("report", "file:report:rows-statement", "date", DAY)));
        Map<String, List<String>> ids = idsByTable(preview);
        assertThat(ids.get("most")).as("5,200 rows: every one, past the 5,000 one answer to the screen holds").hasSize(5_200);
        assertThat(new TreeSet<>(ids.get("most"))).hasSize(5_200);
        assertThat(ids.get("all")).as("6,500 rows, cut at max-rows 6,000").hasSize(6_000);
        assertThat(ids.get("all").get(5_999)).isEqualTo(id(5_999));
        String ops = preview.path("pages").toString();
        assertThat(ops).as("the note above the cut table").contains("The first 6,000 rows only");
        assertThat(preview.path("cut")).hasSize(1);
        assertThat(preview.path("cut").get(0).asText()).startsWith("All: The first 6,000 rows only");
        // the Excel file holds the same rows and says the same
        MvcResult x = call("/api/v1/bi/reports/statement/file", Map.of("report", "file:report:rows-statement", "date", DAY, "format", "xlsx"));
        assertThat(x.getResponse().getStatus()).as(x.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(x.getResponse().getHeaders("X-Statement-Cut")).as("the file says so too, for a script (QA2-204)").hasSize(1)
                .allSatisfy(h -> assertThat(java.net.URLDecoder.decode(h, StandardCharsets.UTF_8)).startsWith("All: The first 6,000 rows only"));
        StringBuilder all = new StringBuilder();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(x.getResponse().getContentAsByteArray()))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                all.append(new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(all.toString()).contains("The first 6,000 rows only").contains(">" + id(5_999) + "<").doesNotContain(">" + id(6_000) + "<");
        Matcher m = Pattern.compile(">" + id(5_100) + "<").matcher(all);
        assertThat(m.find()).as("a row past the 5,000th").isTrue();
    }

    static final String TOKENS = """
            report: token-statement
            title: Tokens
            model: rows-model@1
            statement: { paper: A4, period: none, fontSize: 7, header: { left: "{title}" }, footer: { right: "Page {page} of {pages}" } }
            pages:
              - id: statement
                title: Statement
                layout: statement
                visuals:
                  - { id: intro, type: text, at: { x: 0, y: 0, w: 12, h: 1 }, text: "Intro page {page} of {pages} as of {as-of} for {reader}" }
                  - { id: x, type: table, title: "Desk X p{page}/{pages}", at: { x: 0, y: 1, w: 12, h: 8 }, wells: { columns: [trade.TRADE_ID, trade.DESK] },
                      sort: { by: trade.TRADE_ID }, filters: [{ field: trade.DESK, op: eq, values: [DESK-X] }] }
            """;

    @Test
    void pageAndAsOfTokensWorkInATextBandAndATitleAndAnUnknownParameterIsRefused() throws Exception {
        JsonNode preview = ok(call("/api/v1/bi/reports/statement/preview", Map.of("yaml", TOKENS, "date", DAY)));
        int n = preview.path("pageCount").asInt();
        assertThat(n).as("1,300 rows run over pages").isGreaterThan(2);
        List<String> texts = new ArrayList<>();
        for (JsonNode page : preview.path("pages")) {
            for (JsonNode op : page.path("ops")) {
                texts.add(op.path("text").asText(""));
            }
        }
        assertThat(texts).contains("Intro page 1 of " + n + " as of " + DAY + " for drishti-dev-admin").contains("Desk X p1/" + n)
                .contains("Desk X p2/" + n + " (continued)");
        assertThat(String.join("\n", texts)).doesNotContain("{page}").doesNotContain("{pages}").doesNotContain("{as-of}");
        String bad = TOKENS.replace("left: \"{title}\"", "left: \"{param.nope}\"").replace("Desk X p{page}", "Desk X {nope}");
        JsonNode v = ok(call("/api/v1/bi/reports/validate", Map.of("yaml", bad)));
        assertThat(v.path("valid").asBoolean()).isFalse();
        String problems = v.path("problems").toString();
        assertThat(problems).contains("DRS-9038").contains("\"path\":\"statement.header.left\"").contains("{param.nope} names no parameter")
                .contains("\"path\":\"pages[0].visuals[1].title\"");
        assertThat(call("/api/v1/bi/reports/statement/preview", Map.of("yaml", bad, "date", DAY)).getResponse().getStatus()).as("a bad statement is not drawn")
                .isGreaterThanOrEqualTo(400);
    }

    @Test
    void aStatementIsMadeAsPdfOrXlsxAndEveryOtherFormatGetsTheSameAnswer() throws Exception {
        for (String format : List.of("docx", "png", "csv", "DOCX")) {                    // QA2-11: docx once got "pdf, png, xlsx or csv"
            MvcResult r = call("/api/v1/bi/reports/statement/file", Map.of("report", "file:report:rows-statement", "date", DAY, "format", format));
            assertThat(r.getResponse().getStatus()).as(format).isGreaterThanOrEqualTo(400);
            assertThat(r.getResponse().getContentAsString()).as(format).contains("DRS-9038").contains("a statement is made as pdf, xlsx or jsonl");
        }
    }

    @Test
    void paperAndOrientationAreReadWhateverTheirCase() throws Exception {                 // QA2-13: letter was accepted, Landscape refused
        JsonNode v = ok(call("/api/v1/bi/reports/validate", Map.of("yaml", TOKENS.replace("paper: A4", "paper: letter, orientation: Landscape"))));
        assertThat(v.path("valid").asBoolean()).as(v.toString()).isTrue();
        JsonNode p = ok(call("/api/v1/bi/reports/statement/preview", Map.of("yaml", TOKENS.replace("paper: A4", "paper: letter, orientation: Landscape"), "date", DAY)));
        assertThat(p.path("width").asDouble()).isEqualTo(792.0);
    }
}
