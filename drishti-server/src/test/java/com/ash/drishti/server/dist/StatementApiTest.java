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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The acceptance of paginated reports and statements (docs/architecture/BI_DESIGN.md section 12.2, wave D, accepted when (1); docs/guides/BI_GUIDE.md):
 * a monthly statement on a model bursts through a subscription to ten recipients (two roles), each made as that recipient and mailed as a PDF. Every PDF is
 * page-exact: A4 portrait pages, as many as the footer's {@code Page k of N} says, each with the header (title, period, recipient), the table's column titles
 * on every page it runs onto and the matrix on a page of its own. Two recipients' statements differ only by their masks and row rules: same desk and role,
 * the same text but their name; a desk head sees only their desk's trades with MTM, an analyst every trade with MTM masked. Also the Excel file's print
 * setup, the Studio's preview of draft YAML, and the report schema's checks of a statement ({@code DRS-9038}).
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/stmt-api-${random.uuid}/identity.db", "drishti.packs.overlay=${drishti.test.dir}/stmt-api-overlay/added.yaml",
        "drishti.bi.dir=${drishti.test.dir}/stmt-api-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=${drishti.test.dir}/stmt-api-store/${random.uuid}",
        "drishti.packs.samples=developers", "drishti.sources.connectors-dir=${drishti.test.dir}/stmt-api-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.stmt-lake.plugin=file", "drishti.sources.connectors.stmt-lake.kinds[0]=trade",
        "drishti.sources.connectors.stmt-lake.settings.rescan-seconds=3600",
        "drishti.sources.connectors.stmt-lake.settings.layout.trade.columns=TRADE_ID,DESK,PORTFOLIO,LEGAL_ENTITY,NOTIONAL,MTM,TRADE_DATE",
        "drishti.collab.console-url=https://drishti.test", "drishti.collab.email.from=drishti@localhost", "spring.mail.host=127.0.0.1",
        "spring.mail.properties.mail.smtp.connectiontimeout=4000", "spring.mail.properties.mail.smtp.timeout=4000",
        "drishti.distribution.subscriptions.tick=1h", "drishti.distribution.export.timeout=120s"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StatementApiTest {

    static final String DAY = "2026-09-30";
    static final String REPORT = "file:report:monthly-statement";
    static final String[] DESKS = {"DESK-FX", "DESK-FI", "DESK-EQD"};
    static final int TRADES = 240;
    static final int PORT = freePort();
    static final Charset WIN = Charset.forName("windows-1252");
    /** The ten recipients: user, role, desk. */
    static final String[][] PEOPLE = {{"hfx1", "st-head", "DESK-FX"}, {"hfx2", "st-head", "DESK-FX"}, {"hfi1", "st-head", "DESK-FI"}, {"hfi2", "st-head", "DESK-FI"},
        {"heq1", "st-head", "DESK-EQD"}, {"heq2", "st-head", "DESK-EQD"}, {"ana1", "st-analyst", ""}, {"ana2", "st-analyst", ""}, {"ana3", "st-analyst", ""},
        {"ana4", "st-analyst", ""}};
    static Path root;

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(new ServerSetup(PORT, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)).withPerMethodLifecycle(false);

    @Autowired MockMvc mvc;
    @Autowired TokenVerifier tokens;
    final ObjectMapper json = new ObjectMapper();

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String id(int i) {
        return String.valueOf(70_000 + i);
    }

    static String desk(int i) {
        return DESKS[i % 3];
    }

    /** The lowest MTM of a desk's trades as the statement writes it: a number only that desk's MTM column holds. */
    static final Map<String, String> LOWEST_MTM = Map.of("DESK-FX", "-100,000.00", "DESK-FI", "-75,000.00", "DESK-EQD", "-50,000.00");

    static double mtm(int i) {
        return ((i % 9) - 4) * 25_000.0;
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        root = com.ash.drishti.server.TestRun.temp("drishti-stmt-lake");
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < TRADES; i++) {
            lines.add("{\"id\":\"" + id(i) + "\",\"TRADE_ID\":\"" + id(i) + "\",\"DESK\":\"" + desk(i) + "\",\"PORTFOLIO\":\"BOOK-" + (i % 4) + "\",\"LEGAL_ENTITY\":\"LE-"
                    + (i % 2) + "\",\"NOTIONAL\":" + 1_000_000.0 * (1 + i % 7) + ",\"MTM\":" + mtm(i) + ",\"TRADE_DATE\":\"" + DAY + "\"}");
        }
        Path f = root.resolve("lake").resolve(DAY).resolve("trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
        r.add("drishti.sources.connectors.stmt-lake.settings.root", () -> root.resolve("lake").toString());
        r.add("spring.mail.port", () -> PORT);
    }

    static final String STATEMENT = """
            report: monthly-statement
            title: Monthly statement
            model: st-model@1
            statement:
              paper: A4
              orientation: portrait
              margins: { top: 15, right: 12, bottom: 15, left: 12 }
              header: { left: "{title}", center: "{period}", right: "For {reader}" }
              footer: { left: "Run {run-date}", right: "Page {page} of {pages}" }
              period: previous-month
              fontSize: 9
            pages:
              - id: statement
                title: Statement
                layout: statement
                visuals:
                  - { id: intro, type: text, at: { x: 0, y: 0, w: 12, h: 1 }, text: "Positions of {reader} for {period}", format: { size: heading } }
                  - { id: notional, type: card, title: Notional, at: { x: 0, y: 1, w: 4, h: 2 }, wells: { values: [NOTIONAL] } }
                  - { id: mtm, type: card, title: MTM, at: { x: 4, y: 1, w: 4, h: 2 }, wells: { values: [MTM] } }
                  - { id: count, type: card, title: Trades, at: { x: 8, y: 1, w: 4, h: 2 }, wells: { values: [Trades] } }
                  - { id: positions, type: table, title: Positions, at: { x: 0, y: 3, w: 12, h: 8 },
                      wells: { columns: [trade.DESK, trade.TRADE_ID, trade.PORTFOLIO, NOTIONAL, MTM] }, sort: { by: trade.TRADE_ID }, format: { groupBy: trade.DESK } }
                  - { id: books, type: matrix, title: By book and legal entity, at: { x: 0, y: 11, w: 12, h: 4 },
                      wells: { rows: [trade.PORTFOLIO], columns: [trade.LEGAL_ENTITY], values: [NOTIONAL] }, format: { pageBreak: before } }
            """;

    static {
        try {
            Path folder = com.ash.drishti.server.TestRun.root().resolve("stmt-api-folder");
            Path datasets = Files.createDirectories(folder.resolve("datasets"));
            Path models = Files.createDirectories(folder.resolve("models"));
            Path reports = Files.createDirectories(folder.resolve("reports"));
            Files.writeString(datasets.resolve("st-trade.dataset.yaml"), """
                    rachana: 2
                    dataset: st-trade
                    version: 1.0.0
                    source: { connector: stmt-lake, kind: trade }
                    grain: { keys: [id] }
                    columns:
                      TRADE_ID:     { type: string }
                      DESK:         { type: string }
                      PORTFOLIO:    { type: string }
                      LEGAL_ENTITY: { type: string }
                      NOTIONAL:     { type: decimal, aggregate: sum }
                      MTM:          { type: decimal, aggregate: sum }
                    security:
                      rows:
                        - { role: st-head, where: "DESK in user.desks" }
                        - { role: [st-analyst, st-author, admin], where: "true" }
                      masked: [MTM]
                    """);
            Files.writeString(models.resolve("st-model.model.yaml"), """
                    rachana: 2
                    model: st-model
                    version: 1.0.0
                    datasets:
                      trade: { dataset: st-trade, role: fact }
                    measures:
                      MTM:      { expr: "sum(trade.MTM)" }
                      NOTIONAL: { expr: "sum(trade.NOTIONAL)" }
                      Trades:   { expr: "count(trade)" }
                    """);
            Files.writeString(reports.resolve("monthly-statement.report.yaml"), STATEMENT);
            Files.writeString(reports.resolve("sorted-statement.report.yaml"), STATEMENT.replace("report: monthly-statement", "report: sorted-statement")
                    .replace("sort: { by: trade.TRADE_ID }", "sort: { by: MTM, desc: true }"));
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

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    String admin() {
        return as("drishti-dev-admin", "admin");
    }

    String wes() {
        return as("wes", "st-author");
    }

    @BeforeAll
    void setUp() throws Exception {
        for (String[] r : new String[][] {{"st-head", "true"}, {"st-analyst", "false"}, {"st-author", "true"}}) {
            var res = mvc.perform(put("/api/v1/admin/role-definitions/" + r[0]).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", false, "raw", Boolean.parseBoolean(r[1]), "bi",
                            "st-author".equals(r[0]) ? List.of("bi", "bi-author") : List.of("bi"))))).andReturn().getResponse();
            assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        }
        List<String[]> all = new ArrayList<>(List.of(PEOPLE));
        all.add(new String[] {"wes", "st-author", ""});
        for (String[] u : all) {
            var res = mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", u[0], "displayName", u[0], "email", u[0] + "@desk.test", "desk", u[2], "roles", List.of(u[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andReturn().getResponse();
            assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(201);
        }
    }

    MvcResult call(String method, String path, String who, Object body) throws Exception {
        var req = switch (method) {
            case "POST" -> post(java.net.URI.create(path));
            case "PUT" -> put(java.net.URI.create(path));
            default -> get(java.net.URI.create(path));
        };
        req.header("Authorization", who);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return mvc.perform(req).andReturn();
    }

    JsonNode ok(MvcResult r) throws Exception {
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(200, 299);
        return json.readTree(r.getResponse().getContentAsString());
    }

    // ---- reading a PDF -------------------------------------------------------------------------------------------

    /** A PDF as the test reads it: its page objects, the /Count of its page tree, each page's MediaBox and texts (in drawing order). */
    record Pdf(int pageObjects, int count, List<String> boxes, List<List<String>> pages) {

        String all() {
            return String.join("\n", pages.stream().map(p -> String.join("\n", p)).toList());
        }
    }

    static final Pattern COUNT = Pattern.compile("/Type /Pages /Kids \\[[^\\]]*] /Count (\\d+)");
    static final Pattern BOX = Pattern.compile("/Type /Page /Parent 2 0 R /MediaBox \\[([^\\]]*)]");

    static Pdf read(byte[] pdf) throws Exception {
        String s = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(s).startsWith("%PDF-1.4").endsWith("%%EOF\n");
        List<String> boxes = new ArrayList<>();
        Matcher b = BOX.matcher(s);
        while (b.find()) {
            boxes.add(b.group(1));
        }
        Matcher c = COUNT.matcher(s);
        assertThat(c.find()).isTrue();
        List<List<String>> pages = new ArrayList<>();
        int i = 0;
        while ((i = s.indexOf("stream\n", i)) >= 0) {
            if (i >= 3 && s.startsWith("end", i - 3)) {
                i += 7;
                continue;
            }
            int end = s.indexOf("\nendstream", i);
            byte[] packed = s.substring(i + 7, end).getBytes(StandardCharsets.ISO_8859_1);
            Inflater inf = new Inflater();
            inf.setInput(packed);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inf.finished()) {
                int n = inf.inflate(buf);
                if (n == 0 && inf.needsInput()) {
                    break;
                }
                o.write(buf, 0, n);
            }
            pages.add(texts(new String(o.toByteArray(), StandardCharsets.ISO_8859_1)));
            i = end + 10;
        }
        return new Pdf(boxes.size(), Integer.parseInt(c.group(1)), boxes, pages);
    }

    /** The strings of a content stream's {@code (...) Tj} operators, unescaped and read as WinAnsi. */
    static List<String> texts(String content) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while ((i = content.indexOf(" Tm (", i)) >= 0) {
            StringBuilder t = new StringBuilder();
            int j = i + 5;
            while (content.charAt(j) != ')') {
                char ch = content.charAt(j);
                if (ch == '\\') {
                    j++;
                    ch = content.charAt(j);
                }
                t.append(ch);
                j++;
            }
            out.add(new String(t.toString().getBytes(StandardCharsets.ISO_8859_1), WIN));
            i = j;
        }
        return out;
    }

    static byte[] pdfOf(MimeMessage m) throws Exception {
        Multipart parts = (Multipart) m.getContent();
        for (int i = 0; i < parts.getCount(); i++) {
            Part p = parts.getBodyPart(i);
            if (p.getFileName() != null && p.getFileName().endsWith(".pdf")) {
                return p.getInputStream().readAllBytes();
            }
        }
        throw new AssertionError("no PDF attached");
    }

    Map<String, MimeMessage> mails(int expected) throws Exception {
        long end = System.currentTimeMillis() + 60_000;
        Map<String, MimeMessage> out = new LinkedHashMap<>();
        while (System.currentTimeMillis() < end) {
            out.clear();
            for (MimeMessage m : SMTP.getReceivedMessages()) {
                if (m.getAllRecipients() != null) {
                    out.put(m.getAllRecipients()[0].toString(), m);
                }
            }
            if (out.size() >= expected) {
                return out;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("only " + out.size() + " of " + expected + " mails arrived: " + out.keySet());
    }

    static Set<String> tradeIds(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("\\b7\\d{4}\\b").matcher(text);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    static Set<String> expectedIds(String deskOrAll) {
        Set<String> out = new TreeSet<>();
        for (int i = 0; i < TRADES; i++) {
            if (deskOrAll.isEmpty() || desk(i).equals(deskOrAll)) {
                out.add(id(i));
            }
        }
        return out;
    }

    // ---- the acceptance ------------------------------------------------------------------------------------------

    @Test @Order(1)
    void aMonthlyStatementBurstsToTenRecipientsEachMaskedAndPageExactInPdf() throws Exception {
        Map<String, Object> sub = Map.of("target", Map.of("type", "report", "id", REPORT, "page", "statement",
                "state", Map.of("dates", Map.of("from", "2026-10-01", "to", "2026-10-01"))),
                "schedule", "monthly 1 07:30 business", "format", "pdf", "recipients", List.of("role:st-head", "role:st-analyst"), "title", "Monthly statement");
        ok(call("PUT", "/api/v1/me/subscriptions/monthly", wes(), sub));
        JsonNode run = ok(call("POST", "/api/v1/me/subscriptions/monthly/run", wes(), null));
        assertThat(run.path("status").asText()).as(run.toString()).isEqualTo("ok");
        assertThat(run.path("sent").asInt()).isEqualTo(10);
        Map<String, Integer> recorded = new HashMap<>();
        for (JsonNode d : run.path("deliveries")) {
            assertThat(d.path("statement").asBoolean()).as(d.toString()).isTrue();
            assertThat(d.path("file").asText()).isEqualTo("Monthly statement September 2026 " + d.path("user").asText() + ".pdf");
            recorded.put(d.path("user").asText(), d.path("pages").asInt());
        }
        Map<String, MimeMessage> mails = mails(10);
        Map<String, Pdf> pdfs = new LinkedHashMap<>();
        for (String[] who : PEOPLE) {
            Pdf pdf = read(pdfOf(mails.get(who[0] + "@desk.test")));
            pdfs.put(who[0], pdf);
            int n = pdf.pages().size();
            assertThat(n).as(who[0] + ": the statement runs over pages").isGreaterThanOrEqualTo(2);
            assertThat(pdf.pageObjects()).as("page objects").isEqualTo(n);
            assertThat(pdf.count()).as("the page tree's count").isEqualTo(n);
            assertThat(recorded.get(who[0])).as("the history's page count").isEqualTo(n);
            assertThat(pdf.boxes()).as("every page is A4 portrait").allMatch(b -> b.equals("0 0 595.28 841.89"));
            for (int k = 0; k < n; k++) {
                List<String> page = pdf.pages().get(k);
                assertThat(page).as(who[0] + " page " + (k + 1) + ": the header").contains("Monthly statement", "September 2026", "For " + who[0]);
                assertThat(page).as(who[0] + " page " + (k + 1) + ": the footer").contains("Page " + (k + 1) + " of " + n);
                if (!tradeIds(String.join(" ", page)).isEmpty()) {
                    assertThat(page).as(who[0] + " page " + (k + 1) + ": the table's column titles on every page it runs onto").contains("TRADE_ID", "NOTIONAL", "MTM");
                }
            }
            List<String> last = pdf.pages().get(n - 1);
            assertThat(last).as("the matrix starts a page of its own (pageBreak: before)").contains("By book and legal entity");
            assertThat(String.join(" ", pdf.pages().get(n - 2))).doesNotContain("By book and legal entity");
            assertThat(tradeIds(String.join(" ", last))).isEmpty();
            assertThat(pdf.pages().get(0)).contains("Positions of " + who[0] + " for September 2026");
        }
        // masks per reader and row rules per reader, nothing else
        for (String[] who : PEOPLE) {
            String text = pdfs.get(who[0]).all();
            boolean head = "st-head".equals(who[1]);
            assertThat(tradeIds(text)).as(who[0] + ": the trades their row rule lets them see").isEqualTo(expectedIds(head ? who[2] : ""));
            if (head) {
                assertThat(text).as(who[0] + " reads MTM").doesNotContain(ExportDoc.MASK).contains(LOWEST_MTM.get(who[2]));
                for (String other : DESKS) {
                    if (!other.equals(who[2])) {
                        assertThat(text).as(who[0] + " never sees another desk").doesNotContain(other);
                    }
                }
            } else {
                assertThat(text).as(who[0] + ": MTM is masked").contains(ExportDoc.MASK).doesNotContain(LOWEST_MTM.values().toArray(String[]::new)).contains(DESKS);
            }
        }
        for (String[][] pair : new String[][][] {{PEOPLE[0], PEOPLE[1]}, {PEOPLE[2], PEOPLE[3]}, {PEOPLE[4], PEOPLE[5]}, {PEOPLE[6], PEOPLE[7]}, {PEOPLE[8], PEOPLE[9]}}) {
            String a = pdfs.get(pair[0][0]).all().replace(pair[0][0], "<reader>");
            String b = pdfs.get(pair[1][0]).all().replace(pair[1][0], "<reader>");
            assertThat(a).as(pair[0][0] + " and " + pair[1][0] + " differ only by who they are").isEqualTo(b);
        }
        assertThat(pdfs.get("ana1").pages().size()).as("every trade runs to more pages than one desk's").isGreaterThan(pdfs.get("hfx1").pages().size());
        // the preview made as the same reader lays out the same pages
        JsonNode preview = ok(call("POST", "/api/v1/bi/reports/statement/preview", as("hfi1", "st-head"), Map.of("report", REPORT, "page", "statement",
                "state", Map.of("dates", Map.of("from", "2026-10-01", "to", "2026-10-01")))));
        assertThat(preview.path("pageCount").asInt()).isEqualTo(pdfs.get("hfi1").pages().size());
        assertThat(preview.path("period").asText()).isEqualTo("September 2026");
    }

    @Test @Order(2)
    void theExcelFilePrintsOnTheStatementsPaperWithItsHeaderAndColumnTitlesOnEveryPage() throws Exception {
        MvcResult r = call("POST", "/api/v1/bi/reports/statement/file", as("hfx1", "st-head"), Map.of("report", REPORT, "format", "xlsx",
                "state", Map.of("dates", Map.of("from", "2026-10-01", "to", "2026-10-01"))));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("Monthly%20statement%20September%202026%20hfx1.xlsx");
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(r.getResponse().getContentAsByteArray()))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                parts.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        assertThat(parts.get("xl/workbook.xml")).contains("_xlnm.Print_Titles").contains("Positions");
        String positions = parts.values().stream().filter(x -> x.contains("TRADE_ID") && x.contains("<pageSetup")).findFirst().orElseThrow();
        assertThat(positions).contains("paperSize=\"9\"").contains("orientation=\"portrait\"").contains("&amp;CSeptember 2026").contains("&amp;RPage &amp;P of &amp;N");
        assertThat(tradeIds(positions)).isEqualTo(expectedIds("DESK-FX"));
        MvcResult pdf = call("POST", "/api/v1/bi/reports/statement/file", as("hfx1", "st-head"), Map.of("report", REPORT,
                "state", Map.of("dates", Map.of("from", "2026-10-01", "to", "2026-10-01"))));
        assertThat(pdf.getResponse().getHeader("X-Statement-Pages")).isEqualTo(String.valueOf(read(pdf.getResponse().getContentAsByteArray()).pages().size()));
    }

    @Test @Order(2)
    void withPreferRespondAsyncAStatementIsARunInsideTheWaitTheSameFileElseA202() throws Exception {
        Map<String, Object> body = Map.of("report", REPORT, "format", "xlsx", "state", Map.of("dates", Map.of("from", "2026-10-01", "to", "2026-10-01")));
        MvcResult plain = call("POST", "/api/v1/bi/reports/statement/file", as("hfx1", "st-head"), body);
        var req = post("/api/v1/bi/reports/statement/file").header("Authorization", as("hfx1", "st-head")).header("Prefer", "respond-async, wait=10")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        MvcResult async = mvc.perform(req).andReturn();
        assertThat(async.getResponse().getStatus()).as(async.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(async.getResponse().getHeader("Drishti-Run")).startsWith("r-");
        assertThat(async.getResponse().getHeader("Content-Disposition")).isEqualTo(plain.getResponse().getHeader("Content-Disposition"));
        assertThat(async.getResponse().getHeader("X-Statement-Pages")).isEqualTo(plain.getResponse().getHeader("X-Statement-Pages"));
        assertThat(async.getResponse().getContentAsByteArray()).startsWith((byte) 'P', (byte) 'K');
        var job = post("/api/v1/bi/reports/statement/preview").header("Authorization", as("hfx1", "st-head")).header("Prefer", "respond-async, wait=0")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("report", REPORT)));
        MvcResult accepted = mvc.perform(job).andReturn();
        assertThat(accepted.getResponse().getStatus()).as(accepted.getResponse().getContentAsString()).isEqualTo(202);
        assertThat(json.readTree(accepted.getResponse().getContentAsString()).path("run").path("source").path("type").asText()).isEqualTo("statement");
    }

    @Test @Order(3)
    void anAuthorPreviewsDraftYamlPageByPageAndTheSchemaChecksAStatement() throws Exception {
        String draft = STATEMENT.replace("report: monthly-statement", "report: draft-statement").replace("orientation: portrait", "orientation: landscape")
                .replace("paper: A4", "paper: Letter");
        JsonNode preview = ok(call("POST", "/api/v1/bi/reports/statement/preview", wes(), Map.of("yaml", draft,
                "state", Map.of("dates", Map.of("from", "2026-10-01", "to", "2026-10-01")))));
        assertThat(preview.path("width").asDouble()).isEqualTo(792.0);
        assertThat(preview.path("height").asDouble()).isEqualTo(612.0);
        int n = preview.path("pageCount").asInt();
        assertThat(preview.path("pages")).hasSize(n);
        assertThat(preview.path("pages").get(n - 1).path("ops").toString()).contains("Page " + n + " of " + n);
        assertThat(call("POST", "/api/v1/bi/reports/statement/preview", as("ana1", "st-analyst"), Map.of("yaml", draft)).getResponse().getStatus())
                .as("draft YAML is an author's").isEqualTo(403);
        String head = "report: bad-statement\nmodel: st-model@1\nstatement: { paper: B5, margins: { top: 90 }, header: { left: \"{nope}\" }, period: fortnight }\n"
                + "pages:\n  - id: s\n    title: S\n    layout: statement\n    visuals:\n"
                + "      - { id: c, type: column, at: { x: 0, y: 0, w: 6, h: 4 }, wells: { axis: [trade.DESK], values: [MTM] } }\n"
                + "      - { id: t, type: table, at: { x: 0, y: 4, w: 6, h: 4 }, wells: { columns: [trade.DESK, MTM] }, format: { groupBy: trade.PORTFOLIO, pageBreak: sideways } }\n"
                + "      - { id: k, type: card, at: { x: 6, y: 4, w: 6, h: 2 }, wells: { values: [MTM] } }\n";
        JsonNode v = ok(call("POST", "/api/v1/bi/reports/validate", wes(), Map.of("yaml", head)));
        assertThat(v.path("valid").asBoolean()).isFalse();
        String problems = v.path("problems").toString();
        for (String path : List.of("statement.paper", "statement.margins.top", "statement.header.left", "statement.period", "pages[0].visuals[0].type",
                "pages[0].visuals[1].format.groupBy", "pages[0].visuals[1].format.pageBreak", "pages[0].visuals[1].at")) {
            assertThat(problems).as(path).contains("\"path\":\"" + path + "\"");
        }
        assertThat(problems).contains("DRS-9038");
    }

    /** An Excel file's parts by name ({@code xl/worksheets/sheet1.xml} is the About sheet). */
    static Map<String, String> unzip(byte[] xlsx) throws Exception {
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                parts.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return parts;
    }

    /** The texts of a preview's pages with the given role (a card's value is "value", a table cell "cell"). */
    static List<String> texts(JsonNode preview, String role) {
        List<String> out = new ArrayList<>();
        preview.path("pages").forEach(p -> p.path("ops").forEach(o -> {
            if (role == null || role.equals(o.path("role").asText())) {
                out.add(o.path("text").asText());
            }
        }));
        return out;
    }

    @Test @Order(4)
    void aDateWithNoDataHintsSoAndACardWithNoValueReadsADash() throws Exception {
        // QA2-12, QA2-14: a statement for a date no data covers says so; its cards read a dash (a count reads 0), never blank
        // QA3B-02: the hint names the date the statement read (previous-month, dates: end, so the month's last day), never the whole month, and the nearest
        // date the data holds (the lake holds 2026-09-30 only)
        Map<String, String> hints = Map.of("9999-12-31", "No data is held for 9999-11-30, the date this November 9999 statement reads: every band is empty."
                + " The nearest date with data is 2026-09-30 (before). Choose a date the data covers.",
                "1999-01-01", "No data is held for 1998-12-31, the date this December 1998 statement reads: every band is empty."
                + " The nearest date with data is 2026-09-30 (after). Choose a date the data covers.");
        for (String date : List.of("9999-12-31", "1999-01-01")) {
            JsonNode preview = ok(call("POST", "/api/v1/bi/reports/statement/preview", as("hfx1", "st-head"), Map.of("report", REPORT, "page", "statement",
                    "date", date)));
            assertThat(preview.path("hint").asText()).as(date).isEqualTo(hints.get(date));
            List<String> cards = texts(preview, "value");
            assertThat(cards).as(date + ": Notional, MTM, Trades").hasSize(3).noneMatch(String::isBlank);
            assertThat(cards.subList(0, 2)).as("a sum of nothing").containsExactly("–", "–");
            assertThat(preview.path("rows").asInt()).as(date + ": every band is empty, so no rows (QA4-03), not the empty cards").isZero();
            if (date.startsWith("9999")) {
                // QA4-03: a date after the business date was not counted: the count reads a dash beside its note, never a 0 that reads "no trades",
                // and each card's note is whole (it wraps), never "Not available: DRS-9020 the as-of date 202…"
                assertThat(cards.get(2)).as("not counted").isEqualTo("–");
                List<String> notes = texts(preview, "note");
                assertThat(notes).as(notes.toString()).isNotEmpty().noneMatch(n -> n.endsWith("…"));
                String joined = String.join(" ", notes);
                assertThat(joined).contains("Not available: DRS-9020").contains("9999-11-30");
            } else {
                assertThat(cards.get(2)).as("a count of nothing is 0 on a date the data could hold (QA3B-08)").isEqualTo("0");
            }
        }
        // QA3B-02: a date inside a month the data holds (a month's statement, its last day read) names that date, not the month, and the nearest day held
        String monthly = STATEMENT.replace("report: monthly-statement", "report: month-statement").replace("period: previous-month", "period: month");
        JsonNode inside = ok(call("POST", "/api/v1/bi/reports/statement/preview", wes(), Map.of("yaml", monthly, "page", "statement", "date", "2026-09-29")));
        assertThat(inside.path("hint").asText()).isEqualTo("No data is held for 2026-09-29, the date this September 2026 statement reads: every band is empty."
                + " The nearest date with data is 2026-09-30 (after). Choose a date the data covers.").doesNotContain("2026-09-01 to");
        assertThat(texts(inside, "value").get(2)).as("a count of nothing is 0 here too").isEqualTo("0");
        // QA3B-08: the Excel file reads the same: a count of nothing is 0 (a number), a value of nothing a dash; QA3B-09: "1 page", never "1 pages"
        MvcResult x = call("POST", "/api/v1/bi/reports/statement/file", as("hfx1", "st-head"), Map.of("report", REPORT, "page", "statement", "format", "xlsx",
                "date", "9999-12-31"));
        assertThat(x.getResponse().getStatus()).isEqualTo(200);
        assertThat(java.net.URLDecoder.decode(x.getResponse().getHeader("X-Statement-Hint"), StandardCharsets.UTF_8)).isEqualTo(hints.get("9999-12-31"));
        Map<String, String> sheets = unzip(x.getResponse().getContentAsByteArray());
        String about = sheets.get("xl/worksheets/sheet1.xml");
        assertThat(about).contains("· 2 pages<").contains(hints.get("9999-12-31"));          // the matrix starts its own page; "1 page" in StatementLayoutTest
        assertThat(sheets.get("xl/worksheets/sheet2.xml")).as("Notional").contains(">–<");
        assertThat(sheets.get("xl/worksheets/sheet4.xml")).as("Trades, not counted after the business date (QA4-03)").contains(">–<").doesNotContain("<v>0</v>");
        JsonNode held = ok(call("POST", "/api/v1/bi/reports/statement/preview", as("hfx1", "st-head"), Map.of("report", REPORT, "page", "statement",
                "state", Map.of("dates", Map.of("from", "2026-10-01", "to", "2026-10-01")))));
        assertThat(held.path("hint").isNull()).as("a date with data has no hint").isTrue();
        // a card whose sum comes back as one empty row (its filter leaves no trade) reads a dash too, not a blank (QA2-14)
        String draft = STATEMENT.replace("report: monthly-statement", "report: empty-card")
                .replace("wells: { values: [NOTIONAL] } }", "wells: { values: [NOTIONAL] }, filters: [{ field: trade.DESK, op: in, values: [NOPE] }] }");
        JsonNode empty = ok(call("POST", "/api/v1/bi/reports/statement/preview", wes(), Map.of("yaml", draft, "page", "statement",
                "state", Map.of("dates", Map.of("from", "2026-10-01", "to", "2026-10-01")))));
        assertThat(texts(empty, "value").get(0)).as(empty.toString().substring(0, 300)).isEqualTo("–");
        assertThat(empty.path("hint").isNull()).as("the other bands have data").isTrue();
    }

    @Test @Order(5)
    void burstingNamesWhoIsSkippedAndWhyAndATableSortedByAMaskedMeasureIsDrawnUnsorted() throws Exception {
        var res = mvc.perform(put("/api/v1/admin/role-definitions/st-none").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", false, "raw", false, "bi", List.of())))).andReturn().getResponse();
        assertThat(res.getStatus()).as(res.getContentAsString()).isEqualTo(200);
        for (String[] u : new String[][] {{"hoff", "st-head", "DESK-FX"}, {"nobi", "st-none", ""}}) {
            var made = mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", u[0], "displayName", u[0], "email", u[0] + "@desk.test", "desk", u[2], "roles", List.of(u[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andReturn().getResponse();
            assertThat(made.getStatus()).as(made.getContentAsString()).isEqualTo(201);
        }
        String sorted = "file:report:sorted-statement";
        ok(call("PUT", "/api/v1/me/subscriptions/sorted", wes(), Map.of("target", Map.of("type", "report", "id", sorted, "page", "statement",
                "state", Map.of("dates", Map.of("from", "2026-10-01", "to", "2026-10-01"))), "schedule", "monthly 1 07:30 business", "format", "pdf",
                "recipients", List.of("hoff", "nobi", "ana1", "hfx1"), "title", "Sorted statement")));
        ok(call("POST", "/api/v1/admin/users/hoff/enabled", admin(), Map.of("enabled", false)));
        JsonNode run = ok(call("POST", "/api/v1/me/subscriptions/sorted/run", wes(), null));
        Map<String, JsonNode> by = new HashMap<>();
        run.path("deliveries").forEach(d -> by.put(d.path("user").asText(), d));
        // QA2-17: a disabled recipient is listed as skipped with the reason
        assertThat(by.get("hoff")).as(run.toString()).isNotNull();
        assertThat(by.get("hoff").path("status").asText()).isEqualTo("skipped");
        assertThat(by.get("hoff").path("error").asText()).isEqualTo("the account is disabled");
        // QA2-19: a recipient without access is told in words, not the catalogue's DRS-9001
        assertThat(by.get("nobi").path("status").asText()).isEqualTo("skipped");
        assertThat(by.get("nobi").path("error").asText()).contains("may not open the report").doesNotContain("DRS-");
        // QA2-18: the analyst's table sorted by the MTM masked for them is drawn unsorted, and the delivery says so
        assertThat(by.get("ana1").path("status").asText()).isEqualTo("sent");
        assertThat(by.get("ana1").path("notes").toString()).contains("Not sorted: 'MTM' is masked for you");
        assertThat(by.get("hfx1").path("status").asText()).isEqualTo("sent");
        assertThat(by.get("hfx1").has("notes")).as("MTM is not masked for a desk head: sorted as asked").isFalse();
        JsonNode preview = ok(call("POST", "/api/v1/bi/reports/statement/preview", as("ana1", "st-analyst"), Map.of("report", sorted, "page", "statement",
                "state", Map.of("dates", Map.of("from", "2026-10-01", "to", "2026-10-01")))));
        String all = String.join(" ", texts(preview, null));
        assertThat(tradeIds(all)).as("every trade, drawn unsorted rather than No rows.").isEqualTo(expectedIds(""));
        assertThat(all).contains("Not sorted: 'MTM' is masked for you").doesNotContain("No rows.");
        // QA4-03: the note is whole on paper (it wraps over lines), never cut with an ellipsis
        assertThat(texts(preview, "note")).noneMatch(n -> n.endsWith("…"));
        assertThat(String.join(" ", texts(preview, "note"))).contains("Not sorted: 'MTM' is masked for you, and its order would tell the masked figures.");
        // QA3B-10: the analyst's Excel file carries every note the PDF draws: on the About sheet and next to each table
        MvcResult x = call("POST", "/api/v1/bi/reports/statement/file", as("ana1", "st-analyst"), Map.of("report", sorted, "page", "statement", "format", "xlsx",
                "state", Map.of("dates", Map.of("from", "2026-10-01", "to", "2026-10-01"))));
        assertThat(x.getResponse().getStatus()).isEqualTo(200);
        Map<String, String> sheets = unzip(x.getResponse().getContentAsByteArray());
        String about = sheets.get("xl/worksheets/sheet1.xml");
        assertThat(about).contains(">Notes<").contains("Masked for you: MTM.").contains("Not sorted: 'MTM' is masked for you");
        String positions = sheets.values().stream().filter(s -> s.contains("TRADE_ID") && s.contains("<pageSetup")).findFirst().orElseThrow();
        assertThat(positions).as("the note next to the table").contains("Masked for you: MTM.").contains("Not sorted: 'MTM' is masked for you");
        assertThat(sheets.get("xl/worksheets/sheet3.xml")).as("the MTM card's sheet").contains("Masked for you: MTM.");
        ok(call("POST", "/api/v1/admin/users/hoff/enabled", admin(), Map.of("enabled", true)));
    }
}
