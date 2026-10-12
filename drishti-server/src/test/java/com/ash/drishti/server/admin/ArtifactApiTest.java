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
package com.ash.drishti.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.common.TempFiles;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.EmbedAppStore;
import com.ash.drishti.identity.PreferenceStore;
import com.ash.drishti.server.bi.BiFolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Admin, Artifacts over HTTP on temporary folders: the list, the impact of a pack, a connector and a BI file with real dependants (a
 * child pack, datasets, reports, a board, monitors, alert rules, a subscription, an embed host), refuse-then-force, disable instead,
 * archive and restore, a name clash on restore, an upload with its dry run, and the audit trail.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false", "drishti.packs.enabled=finance"})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ArtifactApiTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    static Path root;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws IOException {
        root = TempFiles.createDir(Path.of("target"), "artifacts-");
        write("installed/artbase/pack.yaml", "pack: artbase\nversion: 1.0.0\ntitle: Art base\nkinds: [artwidget]\n");
        write("installed/artchild/pack.yaml", "pack: artchild\nversion: 1.0.0\ntitle: Art child\nextends: [artbase]\nkinds: [artgadget]\n");
        write("installed/artlone/pack.yaml", "pack: artlone\nversion: 2.1.0\ntitle: Art lone\nkinds: [artsolo]\n");
        write("bi/datasets/art-trades.dataset.yaml", "dataset: art-trades\ntitle: Art trades\ntables:\n  w:\n    source: { connector: art-used, kind: artwidget }\n");
        write("bi/datasets/art-composed.dataset.yaml", "dataset: art-composed\ntitle: Art composed\nsources:\n  - dataset: art-trades\n");
        write("bi/reports/art-overview.report.yaml", "report: art-overview\ntitle: Art overview\ndataset: art-trades\n");
        write("bi/boards/art-wall.board.yaml", "board: art-wall\ntitle: Art wall\ntiles:\n  - { id: t1, report: art-overview, visual: v1 }\n");
        write("connectors/art-boot.yaml", "plugin: file\nkinds: [artboot]\nsettings:\n  root: " + root.resolve("bootdata") + "\n");
        write("bi/python/art-free.python.yaml", "python: art-free\ntitle: Art free\n");
        r.add("drishti.packs.installed-dir", () -> root.resolve("installed").toString());
        r.add("drishti.packs.overlay", () -> root.resolve("added.yaml").toString());
        r.add("drishti.bi.dir", () -> root.resolve("bi").toString());
        r.add("drishti.sources.connectors-dir", () -> root.resolve("connectors").toString());
        r.add("drishti.admin.uninstalled-dir", () -> root.resolve("uninstalled").toString());
        r.add("drishti.admin.hidden-bi-file", () -> root.resolve("hidden.txt").toString());
        r.add("drishti.identity.database-url", () -> "jdbc:sqlite:" + root.resolve("identity/identity.db"));
        r.add("drishti.bi.query.store-dir", () -> root.resolve("bi-store").toString());
    }

    private static void write(String rel, String text) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
    }

    @AfterAll
    static void clean() throws IOException {
        if (root != null && Files.isDirectory(root)) {
            try (Stream<Path> s = Files.walk(root)) {
                for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    @Autowired MockMvc mvc;
    @Autowired PreferenceStore prefs;
    @Autowired AuditLog audit;
    @Autowired BiFolder folder;
    @Autowired EmbedAppStore embeds;

    private static boolean ready;

    @BeforeEach
    void setUp() throws Exception {
        if (ready) {
            return;
        }
        ready = true;
        for (String p : List.of("artbase", "artchild", "artlone")) {
            mvc.perform(post("/api/v1/admin/packs/" + p + "/load")).andExpect(status().isOk());
        }
        mvc.perform(put("/api/v1/admin/connectors/art-used").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plugin\":\"file\",\"kinds\":[\"artwidget\"],\"settings\":{\"root\":\"" + root.resolve("data") + "\"}}")).andExpect(status().isOk());
        prefs.put("tester", "monitors", "art-mon", JSON.readTree("{\"name\":\"art-mon\",\"refs\":[{\"kind\":\"artwidget\",\"id\":\"W-1\"}]}"));
        prefs.put("tester", "alerts", "art-rule", JSON.readTree("{\"name\":\"art-rule\",\"ref\":{\"kind\":\"artgadget\",\"id\":\"G-1\"},\"when\":\"x>1\"}"));
        prefs.put("tester", "subscriptions", "art-sub", JSON.readTree("{\"name\":\"art-sub\",\"target\":{\"type\":\"report\",\"id\":\"file:report:art-overview\"}}"));
        prefs.put("tester", "measure-alerts", "art-ma", JSON.readTree("{\"name\":\"art-ma\",\"source\":{\"type\":\"measure\",\"dataset\":\"art-trades\"}}"));
        prefs.put("tester", "settings", "ui", JSON.readTree("{\"savedSearches\":[{\"name\":\"my widgets\",\"path\":\"/t?kind=artwidget&q=W\"}]}"));
        prefs.put("tester", "workspaces", "art-ws", JSON.readTree("{\"name\":\"art-ws\",\"panes\":[{\"kind\":\"artwidget\",\"entity\":\"W-2\"}]}"));
        embeds.create(new EmbedAppStore.Draft("art-host", "Art host", "ops", List.of("https://art.example"), List.of("artwidget"), List.of("embed:view"), null, null, null, true,
                null, null, null, true), "tester");
        folder.reload();
    }

    private JsonNode json(String url) throws Exception {
        return JSON.readTree(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String act(String path, String body) {
        try {
            return mvc.perform(post("/api/v1/admin/artifacts" + path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode group(JsonNode impact, String category) {
        for (JsonNode g : impact.path("groups")) {
            if (g.path("category").asText().equals(category)) {
                return g;
            }
        }
        return JSON.createObjectNode();
    }

    private static List<String> names(JsonNode g) {
        return g.path("items").findValuesAsText("name");
    }

    @Test
    @Order(1)
    void theListHasPacksConnectorsAndBiFilesWithOriginAndState() throws Exception {
        JsonNode all = json("/api/v1/admin/artifacts").path("artifacts");
        JsonNode base = find(all, "pack", "artbase");
        assertThat(base.path("origin").asText()).isEqualTo("archive");
        assertThat(base.path("state").asText()).isEqualTo("on");
        assertThat(base.path("version").asText()).isEqualTo("1.0.0");
        assertThat(find(all, "pack", "finance").path("origin").asText()).isEqualTo("shipped");
        assertThat(find(all, "connector", "art-used").path("kind").asText()).isEqualTo("connector");
        JsonNode ds = find(all, "bi", "art-trades");
        assertThat(ds.path("subtype").asText()).isEqualTo("dataset");
        assertThat(ds.path("origin").asText()).isEqualTo("shipped");
        assertThat(ds.path("link").asText()).isEqualTo("/bi/i/file:dataset:art-trades");
        assertThat(find(all, "bi", "art-wall").path("subtype").asText()).isEqualTo("board");
    }

    private static JsonNode find(JsonNode all, String kind, String name) {
        for (JsonNode a : all) {
            if (a.path("kind").asText().equals(kind) && a.path("name").asText().equals(name)) {
                return a;
            }
        }
        throw new AssertionError("not listed: " + kind + " " + name);
    }

    @Test
    @Order(2)
    void aPacksImpactNamesEveryKindOfDependant() throws Exception {
        JsonNode im = json("/api/v1/admin/artifacts/impact?kind=pack&name=artbase");
        assertThat(im.path("blocked").asBoolean()).isTrue();
        assertThat(names(group(im, "packs"))).containsExactly("artchild");
        assertThat(names(group(im, "datasets"))).contains("art-trades", "art-composed");
        assertThat(names(group(im, "reports"))).containsExactly("art-overview");
        assertThat(names(group(im, "boards"))).containsExactly("art-wall");
        assertThat(names(group(im, "monitors"))).containsExactly("art-mon");
        assertThat(names(group(im, "workspaces"))).containsExactly("art-ws");
        assertThat(names(group(im, "saved-searches"))).containsExactly("my widgets");
        assertThat(names(group(im, "embed-hosts"))).containsExactly("art-host");
        assertThat(names(group(im, "alerts"))).isEmpty();                              // that rule is on artgadget, the child's kind
        JsonNode child = json("/api/v1/admin/artifacts/impact?kind=pack&name=artchild");
        assertThat(names(group(child, "alerts"))).containsExactly("art-rule");
        JsonNode lone = json("/api/v1/admin/artifacts/impact?kind=pack&name=artlone");
        assertThat(lone.path("blocked").asBoolean()).isFalse();
        assertThat(lone.path("summary").asText()).isEqualTo("nothing depends on it");
    }

    @Test
    @Order(3)
    void aConnectorsImpactNamesTheDatasetsThatScanItAndWhatIsOverThem() throws Exception {
        JsonNode im = json("/api/v1/admin/artifacts/impact?kind=connector&name=art-used");
        assertThat(names(group(im, "datasets"))).contains("art-trades", "art-composed");
        assertThat(names(group(im, "reports"))).containsExactly("art-overview");
        assertThat(names(group(im, "boards"))).containsExactly("art-wall");
        assertThat(names(group(im, "monitors"))).containsExactly("art-mon");     // it serves artwidget
        assertThat(im.path("templated").asBoolean()).isFalse();
    }

    @Test
    @Order(4)
    void aBiFilesImpactFollowsDatasetToReportToBoardAndFindsAlertsAndSubscriptions() throws Exception {
        JsonNode im = json("/api/v1/admin/artifacts/impact?kind=bi&subtype=dataset&name=art-trades");
        assertThat(names(group(im, "datasets"))).containsExactly("art-composed");
        assertThat(names(group(im, "reports"))).containsExactly("art-overview");
        assertThat(names(group(im, "boards"))).containsExactly("art-wall");
        assertThat(names(group(im, "measure-alerts"))).containsExactly("art-ma");
        assertThat(names(group(im, "embed-hosts"))).containsExactly("art-host");
        JsonNode rep = json("/api/v1/admin/artifacts/impact?kind=bi&subtype=report&name=art-overview");
        assertThat(names(group(rep, "boards"))).containsExactly("art-wall");
        assertThat(names(group(rep, "subscriptions"))).containsExactly("art-sub");
        JsonNode free = json("/api/v1/admin/artifacts/impact?kind=bi&subtype=python&name=art-free");
        assertThat(free.path("blocked").asBoolean()).isFalse();
        mvc.perform(get("/api/v1/admin/artifacts/impact?kind=bi&subtype=report&name=nope")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DRS-5040"));
    }

    @Test
    @Order(5)
    void uninstallIsRefusedWithTheImpactThenForcedWithTheTypedNameAndArchived() throws Exception {
        mvc.perform(post("/api/v1/admin/artifacts/uninstall").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"pack\",\"name\":\"artbase\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-5041"))
                .andExpect(jsonPath("$.impact.groups[?(@.category=='packs')].items[0].name").value(hasItem("artchild")))
                .andExpect(jsonPath("$.detail").value(containsString("disable it instead")));
        assertThat(root.resolve("installed/artbase/pack.yaml")).exists();
        mvc.perform(post("/api/v1/admin/artifacts/uninstall").contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"pack\",\"name\":\"artbase\",\"cascade\":true,\"confirm\":\"wrong\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("DRS-5043"));
        assertThat(root.resolve("installed/artbase/pack.yaml")).exists();
        JsonNode done = JSON.readTree(act("/uninstall", "{\"kind\":\"pack\",\"name\":\"artbase\",\"cascade\":true,\"confirm\":\"artbase\"}"));
        assertThat(done.path("ok").asBoolean()).isTrue();
        assertThat(done.path("also").findValuesAsText("").toString() + done.path("also")).contains("artchild");
        assertThat(root.resolve("installed/artbase")).doesNotExist();
        assertThat(root.resolve("installed/artchild")).doesNotExist();
        assertThat(root.resolve("uninstalled").resolve(done.path("archive").asText()).resolve("payload/artbase/pack.yaml")).exists();
        // the watcher is nudged at once: the packs are no longer loaded
        mvc.perform(get("/api/v1/admin/packs")).andExpect(jsonPath("$[?(@.name=='artbase')].loaded").value(org.hamcrest.Matchers.not(hasItem(true))));
        // restore it (the child too); a clash is refused until replace is chosen
        JsonNode archives = json("/api/v1/admin/artifacts/uninstalled").path("archives");
        assertThat(archives).hasSizeGreaterThanOrEqualTo(2);
        for (JsonNode a : archives) {
            mvc.perform(post("/api/v1/admin/artifacts/uninstalled/" + a.path("id").asText() + "/restore").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isOk());
        }
        assertThat(root.resolve("installed/artbase/pack.yaml")).exists();
        assertThat(root.resolve("installed/artchild/pack.yaml")).exists();
        assertThat(json("/api/v1/admin/artifacts/uninstalled").path("archives")).isEmpty();
    }

    @Test
    @Order(6)
    void disableIsTheDefaultWayOutAndNeedsTheDependantPacksToGoToo() throws Exception {
        mvc.perform(post("/api/v1/admin/artifacts/disable").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"pack\",\"name\":\"artbase\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-5041"));
        JsonNode off = JSON.readTree(act("/disable", "{\"kind\":\"pack\",\"name\":\"artbase\",\"cascade\":true}"));
        assertThat(off.path("ok").asBoolean()).isTrue();
        assertThat(root.resolve("installed/artbase/pack.yaml")).exists();                        // kept on disk
        assertThat(find(json("/api/v1/admin/artifacts").path("artifacts"), "pack", "artbase").path("state").asText()).isEqualTo("off");
        assertThat(find(json("/api/v1/admin/artifacts").path("artifacts"), "pack", "artchild").path("state").asText()).isEqualTo("off");
        act("/enable", "{\"kind\":\"pack\",\"name\":\"artbase\"}");
        assertThat(find(json("/api/v1/admin/artifacts").path("artifacts"), "pack", "artbase").path("state").asText()).isEqualTo("on");
    }

    @Test
    @Order(7)
    void aPackNamedInTheSiteConfigurationIsNotUninstalledHere() throws Exception {
        mvc.perform(post("/api/v1/admin/artifacts/uninstall").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"pack\",\"name\":\"finance\",\"cascade\":true,\"confirm\":\"finance\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-5044"))
                .andExpect(jsonPath("$.detail").value(containsString("drishti.packs.enabled")));
    }

    @Test
    @Order(8)
    void anUnusedConnectorIsArchivedAndRestoredAndAClashNeedsReplace() throws Exception {
        String body = "{\"plugin\":\"file\",\"kinds\":[\"artfree\"],\"settings\":{\"root\":\"" + root.resolve("data2") + "\"}}";
        mvc.perform(put("/api/v1/admin/connectors/art-free").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        assertThat(json("/api/v1/admin/artifacts/impact?kind=connector&name=art-free").path("blocked").asBoolean()).isFalse();
        JsonNode done = JSON.readTree(act("/uninstall", "{\"kind\":\"connector\",\"name\":\"art-free\"}"));
        String id = done.path("archive").asText();
        assertThat(root.resolve("connectors/art-free.yaml")).doesNotExist();
        assertThat(root.resolve("uninstalled").resolve(id).resolve("payload/art-free.yaml")).exists();
        mvc.perform(get("/api/v1/admin/connectors/art-free")).andExpect(status().isNotFound());
        // someone makes the name again: a restore is refused
        mvc.perform(put("/api/v1/admin/connectors/art-free").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/artifacts/uninstalled/" + id + "/restore").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRS-5042"));
        mvc.perform(post("/api/v1/admin/artifacts/uninstalled/" + id + "/restore").contentType(MediaType.APPLICATION_JSON).content("{\"replace\":true,\"confirm\":\"x\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-5043"));
        JsonNode back = JSON.readTree(mvc.perform(post("/api/v1/admin/artifacts/uninstalled/" + id + "/restore").contentType(MediaType.APPLICATION_JSON)
                .content("{\"replace\":true,\"confirm\":\"art-free\"}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(back.path("replaced").asBoolean()).isTrue();
        assertThat(root.resolve("connectors/art-free.yaml")).exists();
        mvc.perform(get("/api/v1/admin/connectors/art-free")).andExpect(status().isOk());
        // the replaced one is itself in the archive: nothing is lost
        JsonNode archives = json("/api/v1/admin/artifacts/uninstalled").path("archives");
        assertThat(archives).hasSize(1);
        String kept = archives.get(0).path("id").asText();
        mvc.perform(delete("/api/v1/admin/artifacts/uninstalled/" + kept + "?confirm=nope")).andExpect(status().isUnprocessableEntity());
        mvc.perform(delete("/api/v1/admin/artifacts/uninstalled/" + kept + "?confirm=art-free")).andExpect(status().isOk());
        assertThat(root.resolve("uninstalled").resolve(kept)).doesNotExist();
    }

    @Test
    @Order(85)
    void aConnectorThatWasThereAtStartIsGoneAfterItsUninstallNotListedAsApplicationDefined() throws Exception {
        assertThat(find(json("/api/v1/admin/artifacts").path("artifacts"), "connector", "art-boot").path("origin").asText()).isEqualTo("file");
        JsonNode done = JSON.readTree(act("/uninstall", "{\"kind\":\"connector\",\"name\":\"art-boot\"}"));
        assertThat(done.path("ok").asBoolean()).isTrue();
        assertThat(json("/api/v1/admin/artifacts").path("artifacts").findValuesAsText("name")).doesNotContain("art-boot");
        mvc.perform(get("/api/v1/admin/connectors/art-boot")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/artifacts/uninstalled/" + done.path("archive").asText() + "/restore").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(find(json("/api/v1/admin/artifacts").path("artifacts"), "connector", "art-boot").path("origin").asText()).isEqualTo("file");
    }

    @Test
    @Order(9)
    void aConnectorDatasetsScanIsRefusedUntilDependantsAreTakenAlong() throws Exception {
        mvc.perform(post("/api/v1/admin/artifacts/uninstall").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"connector\",\"name\":\"art-used\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.impact.groups[?(@.category=='datasets')].count").value(hasItem(2)));
        JsonNode off = JSON.readTree(act("/disable", "{\"kind\":\"connector\",\"name\":\"art-used\"}"));
        assertThat(off.path("ok").asBoolean()).isTrue();
        assertThat(find(json("/api/v1/admin/artifacts").path("artifacts"), "connector", "art-used").path("state").asText()).isEqualTo("off");
        assertThat(root.resolve("connectors/art-used.yaml")).exists();
        act("/enable", "{\"kind\":\"connector\",\"name\":\"art-used\"}");
    }

    @Test
    @Order(10)
    void biFilesAreHiddenNotDeletedAndAnUnusedOneIsArchived() throws Exception {
        act("/disable", "{\"kind\":\"bi\",\"subtype\":\"report\",\"name\":\"art-overview\"}");
        assertThat(folder.item("file:report:art-overview")).isEmpty();                            // hidden from the catalogue
        assertThat(root.resolve("bi/reports/art-overview.report.yaml")).exists();                 // and kept
        assertThat(Files.readString(root.resolve("hidden.txt"))).contains("file:report:art-overview");
        assertThat(find(json("/api/v1/admin/artifacts").path("artifacts"), "bi", "art-overview").path("state").asText()).isEqualTo("off");
        act("/enable", "{\"kind\":\"bi\",\"subtype\":\"report\",\"name\":\"art-overview\"}");
        assertThat(folder.item("file:report:art-overview")).isPresent();
        mvc.perform(post("/api/v1/admin/artifacts/uninstall").contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"bi\",\"subtype\":\"report\",\"name\":\"art-overview\"}"))
                .andExpect(status().isConflict());
        JsonNode done = JSON.readTree(act("/uninstall", "{\"kind\":\"bi\",\"subtype\":\"python\",\"name\":\"art-free\"}"));
        assertThat(root.resolve("bi/python/art-free.python.yaml")).doesNotExist();
        assertThat(folder.item("file:python:art-free")).isEmpty();
        mvc.perform(post("/api/v1/admin/artifacts/uninstalled/" + done.path("archive").asText() + "/restore").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        assertThat(root.resolve("bi/python/art-free.python.yaml")).exists();
        assertThat(folder.item("file:python:art-free")).isPresent();
    }

    @Test
    @Order(11)
    void uploadingABiFileIsCheckedFirstThenPlacedAndAClashNeedsReplace() throws Exception {
        String good = "dataset: art-new\ntitle: Art new\ntables:\n  w:\n    source: { connector: art-used, kind: artwidget }\n    columns:\n      wid: { type: string, key: true }\n";
        mvc.perform(post("/api/v1/admin/artifacts/bi/check?file=bad name.txt").contentType(MediaType.APPLICATION_OCTET_STREAM).content(good))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.problems[0]").value(containsString("<name>.<kind>.yaml")));
        mvc.perform(post("/api/v1/admin/artifacts/bi/check?file=art-new.dataset.yaml").contentType(MediaType.APPLICATION_OCTET_STREAM).content("dataset: other\n"))
                .andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.problems[0]").value(containsString("rename the file")));
        mvc.perform(post("/api/v1/admin/artifacts/bi/check?file=art-new.dataset.yaml").contentType(MediaType.APPLICATION_OCTET_STREAM).content(good))
                .andExpect(status().isOk()).andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.exists").value(false));
        assertThat(root.resolve("bi/datasets/art-new.dataset.yaml")).doesNotExist();                // a check writes nothing
        mvc.perform(post("/api/v1/admin/artifacts/bi?file=art-new.dataset.yaml").contentType(MediaType.APPLICATION_OCTET_STREAM).content("dataset: other\n"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DRS-5044"));
        mvc.perform(post("/api/v1/admin/artifacts/bi?file=art-new.dataset.yaml").contentType(MediaType.APPLICATION_OCTET_STREAM).content(good)).andExpect(status().isOk());
        assertThat(root.resolve("bi/datasets/art-new.dataset.yaml")).exists();
        assertThat(folder.item("file:dataset:art-new")).isPresent();
        mvc.perform(post("/api/v1/admin/artifacts/bi/check?file=art-new.dataset.yaml").contentType(MediaType.APPLICATION_OCTET_STREAM).content(good))
                .andExpect(jsonPath("$.exists").value(true)).andExpect(jsonPath("$.replacesImpact").value(containsString("Embed hosts (1)")));
        mvc.perform(post("/api/v1/admin/artifacts/bi?file=art-new.dataset.yaml").contentType(MediaType.APPLICATION_OCTET_STREAM).content(good))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DRS-5042"));
        mvc.perform(post("/api/v1/admin/artifacts/bi?file=art-new.dataset.yaml&replace=true&confirm=art-new").contentType(MediaType.APPLICATION_OCTET_STREAM).content(good + "# v2\n"))
                .andExpect(status().isOk());
        assertThat(Files.readString(root.resolve("bi/datasets/art-new.dataset.yaml"))).endsWith("# v2\n");
        assertThat(json("/api/v1/admin/artifacts/uninstalled").path("archives").findValuesAsText("name")).contains("art-new");
    }

    @Test
    @Order(12)
    void everyActionAndEveryRefusalIsAudited() {
        List<AuditLog.Event> events = audit.recent(500, null);
        assertThat(events).extracting(AuditLog.Event::action).contains("artifact-refused", "artifact-uninstalled", "artifact-restored", "artifact-disabled", "artifact-enabled",
                "artifact-purged", "artifact-installed");
        AuditLog.Event e = events.stream().filter(x -> x.action().equals("artifact-uninstalled") && x.subject().equals("pack:artbase")).findFirst().orElseThrow();
        assertThat(e.detail()).contains("impact:", "choice: with dependants", "archived as");
        assertThat(events.stream().filter(x -> x.action().equals("artifact-refused")).map(AuditLog.Event::detail)).anyMatch(d -> d.contains("uninstall refused"));
    }

    @Test
    @Order(13)
    void aPersonalApiTokenNeverReachesIt() throws Exception {
        assertThat(Files.readString(Path.of("src/main/resources/application.yaml"))).contains("/api/v1/admin/artifacts/**");
    }
}
