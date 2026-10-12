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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.server.security.TokenVerifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Distribution over the whole stack, security on, a real BI report over a small generated lake: a report page and a visual exported as Excel, CSV, PDF
 * and PNG as three different people (row rules, masks), the slicer state of the screen applied; a subscription run by hand sends each recipient their
 * own render by SMTP (GreenMail), as an inline body and as a PDF attachment, and the refusals carry their DRS codes.
 */
@SpringBootTest(properties = {"drishti.sources.plugins.demo.settings.ticking=false", "drishti.security.enabled=true",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.packs.enabled=finance",
        "drishti.identity.database-url=jdbc:sqlite:target/dist-api-${random.uuid}/identity.db", "drishti.packs.overlay=target/dist-api-overlay/added.yaml",
        "drishti.bi.dir=target/dist-api-folder", "drishti.bi.folder-refresh=1s", "drishti.bi.query.store-dir=target/dist-api-store/${random.uuid}",
        "drishti.packs.samples=developers", "drishti.sources.connectors-dir=target/test-data/dist-api-connectors", "drishti.sources.plugins.file.enabled=false",
        "drishti.sources.connectors.finance-lake.enabled=false",
        "drishti.sources.connectors.dist-lake.plugin=file", "drishti.sources.connectors.dist-lake.kinds[0]=trade",
        "drishti.sources.connectors.dist-lake.settings.rescan-seconds=3600",
        "drishti.sources.connectors.dist-lake.settings.layout.trade.columns=desk,book,currency,notional,mtm,tradeDate",
        "drishti.collab.console-url=https://drishti.test", "drishti.collab.email.from=drishti@localhost", "spring.mail.host=127.0.0.1",
        "spring.mail.properties.mail.smtp.connectiontimeout=2000", "spring.mail.properties.mail.smtp.timeout=2000",
        "drishti.distribution.subscriptions.tick=1h", "drishti.distribution.export.max-rows=1000"})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DistApiTest {

    static final String REPORT = "file:report:dist-report";
    static final int PORT = freePort();
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

    static void day(String day) throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            lines.add("{\"id\":\"T-" + day.substring(8) + "-" + i + "\",\"tradeId\":\"T-" + day.substring(8) + "-" + i + "\",\"desk\":\"" + (i % 3 == 0 ? "DESK-A" : i % 3 == 1 ? "DESK-B" : "DESK-C")
                    + "\",\"book\":\"BOOK-" + (i % 3 + 1) + "\",\"currency\":\"" + (i % 2 == 0 ? "USD" : "EUR") + "\",\"notional\":" + 1000.0 * (i + 1) + ",\"mtm\":" + ((i % 5) - 2) * 100.0
                    + ",\"tradeDate\":\"" + day + "\"}");
        }
        Path f = root.resolve("lake").resolve(day).resolve("trade.jsonl");
        Files.createDirectories(f.getParent());
        Files.write(f, lines, StandardCharsets.UTF_8);
    }

    @DynamicPropertySource
    static void lake(DynamicPropertyRegistry r) throws Exception {
        root = Files.createTempDirectory("drishti-dist-lake");
        day("2026-10-07");
        day("2026-10-08");
        r.add("drishti.sources.connectors.dist-lake.settings.root", () -> root.resolve("lake").toString());
        r.add("spring.mail.port", () -> PORT);
    }

    static {
        try {
            Path datasets = Path.of("target/dist-api-folder/datasets");
            Path reports = Path.of("target/dist-api-folder/reports");
            Files.createDirectories(datasets);
            Files.createDirectories(reports);
            Files.writeString(datasets.resolve("dist-lake.dataset.yaml"), """
                    rachana: 1
                    dataset: dist-lake
                    title: Lake for distribution
                    tables:
                      trades:
                        source: { connector: dist-lake, kind: trade }
                        columns:
                          tradeId: { type: string, key: true }
                          desk: string
                          book: string
                          currency: string
                          notional: decimal
                          mtm: decimal
                          tradeDate: date
                    measures:
                      Notional: { expr: "sum(trades.notional)" }
                      Trades: { expr: "count(trades)" }
                    security:
                      rows:
                        - { role: desk-head, where: "trades.desk in user.desks" }
                        - { role: bi-risk, where: "true" }
                        - { role: bi-analyst, where: "true" }
                        - { role: report-author, where: "true" }
                        - { role: admin, where: "true" }
                      masked: [mtm]
                    """);
            Files.writeString(reports.resolve("dist-report.report.yaml"), """
                    report: dist-report
                    title: Distribution report
                    dataset: dist-lake
                    pages:
                      - id: main
                        title: Main
                        visuals:
                          - { id: total, type: card, title: Notional, at: { x: 0, y: 0, w: 3, h: 2 }, wells: { values: [Notional] } }
                          - { id: ccy, type: slicer, title: Currency, at: { x: 3, y: 0, w: 3, h: 2 }, wells: { field: [trades.currency] }, format: { mode: list } }
                          - { id: by-desk, type: bar, title: Notional by desk, at: { x: 0, y: 2, w: 6, h: 4 }, wells: { axis: [trades.desk], values: [Notional] } }
                          - { id: pnl, type: table, title: Trades with MTM, at: { x: 6, y: 2, w: 6, h: 4 }, wells: { columns: [trades.tradeId, trades.desk, trades.mtm] }, limit: 6 }
                      - id: other
                        title: Other
                        visuals: []
                    """);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    String as(String user, String... roles) {
        return "Bearer " + tokens.mint(user, List.of(roles), 300);
    }

    String admin() {
        return as("drishti-dev-admin", "admin");
    }

    @BeforeAll
    void setUp() throws Exception {
        role("bi-risk", List.of("bi"), true);
        role("desk-head", List.of("bi"), true);
        role("bi-analyst", List.of("bi"), false);
        role("report-author", List.of("bi", "bi-author"), false);
        role("plain", List.of(), false);
        for (String u : List.of("rita:bi-risk:", "hana:desk-head:DESK-A", "ann:bi-analyst:", "wes:report-author:", "nobi:plain:")) {
            String[] n = u.split(":", -1);
            mvc.perform(post("/api/v1/admin/users").header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("username", n[0], "displayName", n[0], "email", n[0] + "@desk.test", "desk", n[2], "roles", List.of(n[1]),
                            "packs", List.of("finance"), "password", "long-enough-pass-1")))).andExpect(status().isCreated());
        }
    }

    void role(String name, List<String> bi, boolean raw) throws Exception {
        var r = mvc.perform(put("/api/v1/admin/role-definitions/" + name).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("kinds", List.of("*"), "approve", false, "raw", raw, "bi", bi)))).andReturn().getResponse();
        assertThat(r.getStatus()).as(r.getContentAsString()).isEqualTo(200);
    }

    ResultActions send(String method, String path, String who, Object body) throws Exception {
        var req = switch (method) {
            case "POST" -> post(java.net.URI.create(path));
            case "PUT" -> put(java.net.URI.create(path));
            case "DELETE" -> delete(java.net.URI.create(path));
            default -> get(java.net.URI.create(path));
        };
        req.header("Authorization", who);
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : json.writeValueAsString(body));
        }
        return mvc.perform(req);
    }

    JsonNode ok(ResultActions r) throws Exception {
        return json.readTree(r.andExpect(status().is2xxSuccessful()).andReturn().getResponse().getContentAsString());
    }

    byte[] file(String format, String who, String body) throws Exception {
        return send("POST", "/api/v1/export/" + format, who, body).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
    }

    static Map<String, String> unzip(byte[] xlsx) throws Exception {
        Map<String, String> out = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                out.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    static final String DATES = "\"dates\":{\"from\":\"2026-10-08\",\"to\":\"2026-10-08\"}";

    static String target(String extra) {
        return "{\"type\":\"report\",\"id\":\"" + REPORT + "\"," + extra + "}";
    }

    // ---- export -------------------------------------------------------------------------------------------------------

    @Test @Order(0)
    void theStatusSaysWhatTheCallerCanDo() throws Exception {
        mvc.perform(get("/api/v1/distribution/status").header("Authorization", as("rita", "bi-risk"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.export.enabled").value(true)).andExpect(jsonPath("$.subscriptions.enabled").value(true))
                .andExpect(jsonPath("$.subscriptions.perUser").isNumber()).andExpect(jsonPath("$.subscriptions.mine").isNumber());
    }

    @Test @Order(1)
    void aPageIsExportedAsExcelWithOneTypedSheetPerVisualAndTheReadersOwnRowsAndMasks() throws Exception {
        String body = target("\"page\":\"main\",\"state\":{" + DATES + "}");
        Map<String, String> rita = unzip(file("xlsx", as("rita", "bi-risk"), body));
        assertThat(rita.get("xl/workbook.xml")).contains("Notional by desk").contains("Trades with MTM").contains("About");
        assertThat(rita.get("xl/worksheets/sheet1.xml")).contains("Distribution report").contains("Data as of 2026-10-08").contains("Dates: 2026-10-08 to 2026-10-08");
        String ritaBars = rita.get("xl/worksheets/sheet3.xml");
        assertThat(ritaBars).contains("DESK-A").contains("DESK-B").contains("DESK-C");
        assertThat(ritaBars).contains("<v>92000.0</v>");                      // DESK-A's notional, typed as a number
        Map<String, String> hana = unzip(file("xlsx", as("hana", "desk-head"), body));
        assertThat(hana.get("xl/worksheets/sheet3.xml")).as("a desk head's export holds her desk only").contains("DESK-A").doesNotContain("DESK-B").doesNotContain("DESK-C");
        Map<String, String> ann = unzip(file("xlsx", as("ann", "bi-analyst"), body));
        String annRows = ann.get("xl/worksheets/sheet4.xml");
        String ritaRows = rita.get("xl/worksheets/sheet4.xml");
        assertThat(ritaRows).as("rita may see MTM").containsPattern("<c r=\"C\\d+\" s=\"3\"><v>-?\\d+(\\.\\d+)?</v></c>");
        assertThat(annRows).as("ann's MTM is masked: no number in that column").doesNotContainPattern("<c r=\"C\\d+\" s=\"[23]\">");
    }

    @Test @Order(2)
    void theScreensSlicerStateAndASingleVisualAreHonoured() throws Exception {
        String whole = new String(file("csv", as("rita", "bi-risk"), target("\"page\":\"main\",\"visual\":\"by-desk\",\"state\":{" + DATES + "}")), StandardCharsets.UTF_8);
        String usd = new String(file("csv", as("rita", "bi-risk"), target("\"page\":\"main\",\"visual\":\"by-desk\",\"state\":{" + DATES + ",\"slicers\":{\"ccy\":[\"USD\"]}}")),
                StandardCharsets.UTF_8);
        assertThat(whole).contains("desk,Notional").contains("DESK-B,");
        assertThat(usd).contains("desk,Notional");
        assertThat(usd).as("the USD slicer changes the numbers").isNotEqualTo(whole);
        assertThat(whole.lines().filter(l -> l.startsWith("DESK-")).count()).isEqualTo(3);
        assertThat(whole).doesNotContain("Trades with MTM");                                                            // one visual only
    }

    @Test @Order(3)
    void pdfAndPngAreRealFilesAndTheRefusalsCarryTheirCodes() throws Exception {
        byte[] pdf = file("pdf", as("rita", "bi-risk"), target("\"page\":\"main\",\"state\":{" + DATES + "}"));
        assertThat(new String(pdf, 0, 8, StandardCharsets.ISO_8859_1)).startsWith("%PDF-1.4");
        byte[] png = file("png", as("rita", "bi-risk"), target("\"page\":\"main\",\"state\":{" + DATES + "}"));
        assertThat(png).startsWith(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
        send("POST", "/api/v1/export/docx", as("rita", "bi-risk"), target("\"page\":\"main\"")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-9060"));
        send("POST", "/api/v1/export/pdf", as("nobi", "plain"), target("\"page\":\"main\"")).andExpect(status().isForbidden());
        send("POST", "/api/v1/export/pdf", as("rita", "bi-risk"), target("\"page\":\"nope\"")).andExpect(status().isBadRequest());
        send("POST", "/api/v1/export/pdf", as("rita", "bi-risk"), "{\"type\":\"view\",\"id\":\"trade\"}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-9060"));
    }

    // ---- subscriptions ------------------------------------------------------------------------------------------------

    static String text(MimeMessage m, String type) throws Exception {
        return part(m, type);
    }

    static String part(Part p, String type) throws Exception {
        if (p.isMimeType(type)) {
            return String.valueOf(p.getContent());
        }
        if (p.isMimeType("multipart/*")) {
            Multipart m = (Multipart) p.getContent();
            for (int i = 0; i < m.getCount(); i++) {
                String r = part(m.getBodyPart(i), type);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    MimeMessage mailTo(String address) throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            for (MimeMessage m : SMTP.getReceivedMessages()) {
                if (m.getAllRecipients() != null && m.getAllRecipients()[0].toString().equals(address)) {
                    return m;
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no mail to " + address);
    }

    @Test @Order(10)
    void aSubscriptionRunByHandMailsEachRecipientTheirOwnRenderAndHistoryAudits() throws Exception {
        Map<String, Object> sub = Map.of("target", Map.of("type", "report", "id", REPORT, "page", "main", "visual", "",
                "state", Map.of("dates", Map.of("from", "2026-10-08", "to", "2026-10-08"))),
                "schedule", "business-days 07:30", "format", "html", "recipients", List.of("rita", "hana", "ann"));
        ok(send("PUT", "/api/v1/me/subscriptions/morning", as("wes", "report-author"), sub));
        assertThat(ok(send("GET", "/api/v1/me/subscriptions", as("wes", "report-author"), null))).hasSize(1);
        JsonNode run = ok(send("POST", "/api/v1/me/subscriptions/morning/run", as("wes", "report-author"), null));
        assertThat(run.path("status").asText()).isEqualTo("ok");
        assertThat(run.path("sent").asInt()).isEqualTo(3);
        MimeMessage toRita = mailTo("rita@desk.test");
        MimeMessage toHana = mailTo("hana@desk.test");
        MimeMessage toAnn = mailTo("ann@desk.test");
        String rita = text(toRita, "text/html");
        String hana = text(toHana, "text/html");
        String ann = text(toAnn, "text/html");
        assertThat(rita).contains("DESK-B").contains("Trades with MTM");
        assertThat(hana).as("hana's mail holds her desk only").contains("DESK-A").doesNotContain("DESK-B").doesNotContain("DESK-C");
        assertThat(ann).contains("DESK-B");
        assertThat(rita).isNotEqualTo(ann);                                                                              // different masks, different mail
        assertThat(toRita.getSubject()).contains("Distribution report");
        assertThat(toRita.getHeader("Message-ID")[0]).isNotEqualTo(toAnn.getHeader("Message-ID")[0]);
        JsonNode mine = ok(send("GET", "/api/v1/me/subscriptions/morning", as("wes", "report-author"), null));
        assertThat(mine.path("runs")).hasSize(1);
        assertThat(mine.path("runs").get(0).path("deliveries")).hasSize(3);
        assertThat(ok(send("GET", "/api/v1/admin/subscriptions", admin(), null))).hasSize(1);
        send("GET", "/api/v1/admin/subscriptions", as("rita", "bi-risk"), null).andExpect(status().isForbidden());
    }

    @Test @Order(11)
    void aPdfSubscriptionAttachesTheRecipientsOwnPdfAndPauseResumeWork() throws Exception {
        SMTP.purgeEmailFromAllMailboxes();
        Map<String, Object> sub = Map.of("target", Map.of("type", "report", "id", REPORT, "page", "main"), "schedule", "weekly mon 08:00 business", "format", "pdf",
                "recipients", List.of("rita"), "date", "previous");
        ok(send("PUT", "/api/v1/me/subscriptions/pdf", as("wes", "report-author"), sub));
        ok(send("POST", "/api/v1/me/subscriptions/pdf/run", as("wes", "report-author"), null));
        MimeMessage m = mailTo("rita@desk.test");
        Multipart parts = (Multipart) m.getContent();
        boolean pdf = false;
        for (int i = 0; i < parts.getCount(); i++) {
            Part p = parts.getBodyPart(i);
            if (p.getFileName() != null && p.getFileName().endsWith(".pdf")) {
                byte[] bytes = p.getInputStream().readAllBytes();
                assertThat(new String(bytes, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
                pdf = true;
            }
        }
        assertThat(pdf).as("a PDF is attached").isTrue();
        assertThat(ok(send("POST", "/api/v1/me/subscriptions/pdf/pause", as("wes", "report-author"), null)).path("enabled").asBoolean()).isFalse();
        JsonNode resumed = ok(send("POST", "/api/v1/me/subscriptions/pdf/resume", as("wes", "report-author"), null));
        assertThat(resumed.path("enabled").asBoolean()).isTrue();
        assertThat(resumed.path("nextRun").asText()).isNotBlank();
        JsonNode preview = ok(send("POST", "/api/v1/subscriptions/preview", as("wes", "report-author"), Map.of("schedule", "monthly last 18:00 business")));
        assertThat(preview.path("next")).hasSize(3);
        ok(send("POST", "/api/v1/subscriptions/preview", as("wes", "report-author"), Map.of("schedule", "after-load")).andExpect(status().isOk()));
    }

    @Test @Order(12)
    void theRulesAreEnforcedWithCodes() throws Exception {
        Map<String, Object> mine = Map.of("target", Map.of("type", "report", "id", REPORT), "schedule", "daily 07:00", "format", "html", "recipients", List.of("rita"));
        send("PUT", "/api/v1/me/subscriptions/others", as("ann", "bi-analyst"), mine).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-9061"));
        send("PUT", "/api/v1/me/subscriptions/mine", as("ann", "bi-analyst"), Map.of("target", Map.of("type", "report", "id", REPORT), "schedule", "daily 07:00", "format", "html",
                "recipients", List.of("ann"))).andExpect(status().isOk());
        send("PUT", "/api/v1/me/subscriptions/ext", as("wes", "report-author"), Map.of("target", Map.of("type", "report", "id", REPORT), "schedule", "daily 07:00", "format", "html",
                "recipients", List.of("someone@elsewhere.com"))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-9061"));
        send("PUT", "/api/v1/me/subscriptions/nobody", as("nobi", "plain"), Map.of("target", Map.of("type", "report", "id", REPORT), "schedule", "daily 07:00", "format", "html",
                "recipients", List.of("nobi"))).andExpect(status().isUnprocessableEntity());
        send("POST", "/api/v1/me/subscriptions/ghost/run", as("ann", "bi-analyst"), null).andExpect(status().isNotFound());
        send("DELETE", "/api/v1/me/subscriptions/mine", as("ann", "bi-analyst"), null).andExpect(status().isNoContent());
        send("DELETE", "/api/v1/admin/subscriptions/wes/pdf", admin(), null).andExpect(status().isNoContent());
        send("DELETE", "/api/v1/admin/subscriptions/wes/pdf", admin(), null).andExpect(status().isNotFound());
    }
}
