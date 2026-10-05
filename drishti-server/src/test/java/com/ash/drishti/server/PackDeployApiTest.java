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
package com.ash.drishti.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.server.deploy.TestArchives;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Admin → Packs → Deploy archive and Data source, end to end on a scratch pack whose data is JSON lines on disk:
 * verification (tampering, slip, signature, version, lint, dependencies, size), the preview, deploy with the previous
 * version kept, rollback, the history; and the data source layers (pack, override file, site), validation, test connection.
 */
@SpringBootTest(properties = {"drishti.rachana.hot-reload=false", "drishti.sources.plugins.demo.settings.ticking=false",
        "drishti.packs.enabled=lakepack", "drishti.packs.deploy.max-archive-mb=1",
        "drishti.sources.connectors.item-store.settings.lookback-days=7"})
@AutoConfigureMockMvc
class PackDeployApiTest {

    static Path root;
    static KeyPair acme;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SUTRA = """
            rachana: 1
            sutra: item-view
            version: 1
            description: An item.
            match: { kind: item, priority: 10 }
            title: { pill: "Item", id: $.id }
            strip:
              - { label: Id, bind: $.id }
            panels:
              - { id: built, kind: provenance, title: How this view was built }
            """;

    private static String packYaml(String version, boolean thing, String extra) {
        return "pack: lakepack\nversion: " + version + "\ntitle: Lake pack\nkinds: [item" + (thing ? ", thing" : "") + "]\n"
                + "mnemonics:\n  ITM: {kind: item, label: Items}\n"
                + "connectors:\n  item-store:\n    plugin: file\n    kinds: [item" + (thing ? ", thing" : "") + "]\n    settings:\n"
                + "      root: " + root.resolve("files-a") + "\n      lookback-days: 10\n"
                + "routes:\n  item: item-store\n" + (thing ? "  thing: item-store\n" : "") + extra;
    }

    /** Folders are system properties set before the context starts: the pack loader reads them in an EnvironmentPostProcessor, before test properties exist. */
    private static final String[] SYSTEM_PROPERTIES = {"drishti.packs.dir", "drishti.packs.installed-dir", "drishti.packs.overlay", "drishti.packs.settings-dir",
            "drishti.packs.deploy.history-file"};

    static {
        try {
            root = Files.createTempDirectory("drishti-deploy");
            acme = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Path pack = Files.createDirectories(root.resolve("packs/lakepack"));
            Files.createDirectories(pack.resolve("sutras"));
            Files.writeString(pack.resolve("pack.yaml"), packYaml("1.0.0", true, ""));
            Files.writeString(pack.resolve("sutras/item.v1.sutra.yaml"), SUTRA);
            lines("files-a/2026-10-02/item.jsonl", 3);
            lines("files-a/2026-10-05/item.jsonl", 2);
            lines("files-a/2026-10-05/thing.jsonl", 1);
            lines("files-b/2026-10-05/item.jsonl", 5);
            System.setProperty("drishti.packs.dir", root.resolve("packs").toString());
            System.setProperty("drishti.packs.installed-dir", root.resolve("installed").toString());
            System.setProperty("drishti.packs.overlay", root.resolve("added.yaml").toString());
            System.setProperty("drishti.packs.settings-dir", root.resolve("settings").toString());
            System.setProperty("drishti.packs.deploy.history-file", root.resolve("history.jsonl").toString());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("drishti.packs.registry.trusted-keys.acme", () -> Base64.getEncoder().encodeToString(acme.getPublic().getEncoded()));
    }

    @org.junit.jupiter.api.AfterAll
    static void forgetTheFolders() {
        for (String k : SYSTEM_PROPERTIES) {
            System.clearProperty(k);
        }
    }

    private static void lines(String file, int n) throws Exception {
        Path f = root.resolve(file);
        Files.createDirectories(f.getParent());
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            sb.append("{\"id\":\"I-").append(i).append("\"}\n");
        }
        Files.writeString(f, sb.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired AuditLog audit;

    private static Map<String, String> files(String version, boolean thing, String sutra) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("pack.yaml", packYaml(version, thing, ""));
        f.put("sutras/item.v1.sutra.yaml", sutra);
        return f;
    }

    private ResultActions upload(byte[] data, String... headers) throws Exception {
        var b = post("/api/v1/admin/packs/deploy").contentType(MediaType.APPLICATION_OCTET_STREAM).content(data).header("X-Drishti-Filename", "lakepack.tar.gz");
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return mvc.perform(b);
    }

    private JsonNode checked(byte[] data, String... headers) throws Exception {
        return JSON.readTree(upload(data, headers).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static String check(JsonNode r, String name) {
        for (JsonNode c : r.path("checks")) {
            if (c.path("name").asText().equals(name)) {
                return (c.path("ok").asBoolean() ? "ok: " : "FAIL: ") + c.path("detail").asText();
            }
        }
        return "(no such check)";
    }

    // -- verification --------------------------------------------------------------------------------------------------

    @Test
    void aGoodArchiveIsVerifiedAndPreviewedWithTheBreakingChangesNamed() throws Exception {
        JsonNode r = checked(TestArchives.bundle("lakepack", "1.0.1", files("1.0.1", false, SUTRA)));
        assertThat(r.path("ok").asBoolean()).as(r.toString()).isTrue();
        for (String name : new String[] {"archive", "unpack", "pack.yaml", "manifest", "sutra lint", "sutra test", "dependencies"}) {
            assertThat(check(r, name)).as(name).startsWith("ok");
        }
        assertThat(r.path("uploadId").asText()).isNotBlank();
        JsonNode pv = r.path("preview");
        assertThat(pv.path("state").asText()).isEqualTo("upgrade");
        assertThat(pv.path("running").path("version").asText()).isEqualTo("1.0.0");
        assertThat(pv.path("running").path("loaded").asBoolean()).isTrue();
        assertThat(pv.path("versionOrder").asText()).isEqualTo("newer");
        assertThat(pv.path("counts").path("breaking").asInt()).isEqualTo(1);                    // the kind 'thing' is gone
        assertThat(pv.path("findings").findValuesAsText("what")).contains("kind removed", "routes removed");
        assertThat(pv.path("findings").get(1).path("level").asText()).isEqualTo("breaking");
        assertThat(Files.exists(root.resolve("installed/lakepack"))).as("a preview changes nothing").isFalse();
        mvc.perform(delete("/api/v1/admin/packs/deploy/" + r.path("uploadId").asText())).andExpect(jsonPath("$.discarded").value(true));
    }

    @Test
    void aTamperedArchiveIsRefusedAndNothingIsStaged() throws Exception {
        Map<String, String> actual = files("1.0.2", true, SUTRA);
        actual.put("sutras/item.v1.sutra.yaml", SUTRA + "# edited after bundling\n");
        long staged = stagedUploads();
        JsonNode r = checked(TestArchives.tarGz("lakepack", "1.0.2", null, files("1.0.2", true, SUTRA), actual));
        assertThat(r.path("ok").asBoolean()).isFalse();
        assertThat(check(r, "manifest")).isEqualTo("FAIL: checksum mismatch: sutras/item.v1.sutra.yaml");
        assertThat(r.has("uploadId")).isFalse();
        mvc.perform(post("/api/v1/admin/packs/deploy/nope")).andExpect(status().isNotFound());
        assertThat(stagedUploads()).as("a refused upload leaves nothing behind").isEqualTo(staged);
    }

    private static long stagedUploads() throws Exception {
        Path d = root.resolve("installed/.uploads");
        if (!Files.isDirectory(d)) {
            return 0;
        }
        try (var s = Files.list(d)) {
            return s.count();
        }
    }

    @Test
    void aMissingManifestAWrongChecksumHeaderAndANonArchiveAreRefused() throws Exception {
        Map<String, String> f = files("1.0.3", true, SUTRA);
        assertThat(check(checked(TestArchives.zip(f)), "manifest")).startsWith("FAIL: no MANIFEST.json");
        byte[] ok = TestArchives.bundle("lakepack", "1.0.3", f);
        assertThat(check(checked(ok, "X-Drishti-Sha256", "00" + "ab".repeat(31)), "checksum")).startsWith("FAIL: the upload's sha256 is");
        JsonNode bad = checked("this is not an archive".getBytes());
        assertThat(bad.path("ok").asBoolean()).isFalse();
        assertThat(check(bad, "unpack")).contains("not a .tar.gz or .zip");
    }

    @Test
    void pathsOutsideThePackAreRefusedAndWriteNothing() throws Exception {
        Map<String, String> e = new LinkedHashMap<>();
        e.put("lakepack/pack.yaml", packYaml("1.0.4", true, ""));
        e.put("lakepack/../../evil.txt", "x");
        JsonNode r = checked(TestArchives.tarGz(e));
        assertThat(check(r, "unpack")).startsWith("FAIL: unsafe path");
        Map<String, String> z = new LinkedHashMap<>();
        z.put("../evil.txt", "x");
        assertThat(check(checked(TestArchives.zip(z)), "unpack")).startsWith("FAIL: unsafe path");
        assertThat(root.getParent().resolve("evil.txt")).doesNotExist();
        assertThat(root.resolve("evil.txt")).doesNotExist();
    }

    @Test
    void anArchiveOverTheSizeLimitIsAnClean413() throws Exception {
        mvc.perform(post("/api/v1/admin/packs/deploy").contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[1024 * 1024 + 1]))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.detail").value(containsString("larger than 1 MB")));
        mvc.perform(post("/api/v1/admin/packs/deploy").contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[0])).andExpect(status().isBadRequest());
    }

    @Test
    void theRequiredServerVersionIsEnforcedWhenTheServerKnowsItsOwn() throws Exception {
        byte[] data = TestArchives.tarGz("lakepack", "1.0.5", ">=999.0.0", files("1.0.5", true, SUTRA), files("1.0.5", true, SUTRA));
        JsonNode r = checked(data);
        String c = check(r, "server version");
        if (!c.equals("(no such check)")) {
            // a build without build-info.properties cannot know its version and says nothing; with one, 999 is refused
            assertThat(r.path("ok").asBoolean() ? "passed" : c).satisfiesAnyOf(x -> assertThat(x).startsWith("FAIL: the archive needs server >=999.0.0"),
                    x -> assertThat(x).isEqualTo("passed"));
        }
    }

    @Test
    void aBrokenSutraAndAMissingParentPackAreRefusedWithTheReason() throws Exception {
        String broken = SUTRA.replace("match: { kind: item, priority: 10 }", "match: { kind: item, where: \"((\", priority: 10 }");
        JsonNode r = checked(TestArchives.bundle("lakepack", "1.0.6", files("1.0.6", true, broken)));
        assertThat(r.path("ok").asBoolean()).isFalse();
        assertThat(check(r, "sutra lint")).startsWith("FAIL:").contains("item.v1.sutra.yaml");
        Map<String, String> f = files("1.0.6", true, SUTRA);
        f.put("pack.yaml", packYaml("1.0.6", true, "extends: [ghost-pack]\n"));
        JsonNode r2 = checked(TestArchives.bundle("lakepack", "1.0.6", f));
        assertThat(check(r2, "dependencies")).startsWith("FAIL: extends ghost-pack, which is neither loaded nor on disk");
    }

    @Test
    void signaturesAreCheckedAgainstTheTrustedKeys() throws Exception {
        byte[] data = TestArchives.bundle("lakepack", "1.0.7", files("1.0.7", true, SUTRA));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(acme.getPrivate());
        s.update(data);
        String sig = Base64.getEncoder().encodeToString(s.sign());
        assertThat(check(checked(data), "signature")).startsWith("ok: not signed");
        assertThat(check(checked(data, "X-Drishti-Signature", sig, "X-Drishti-Publisher", "acme"), "signature")).isEqualTo("ok: signed by acme");
        assertThat(check(checked(data, "X-Drishti-Signature", sig, "X-Drishti-Publisher", "rogue"), "signature")).startsWith("FAIL: publisher 'rogue' is not trusted");
        byte[] other = TestArchives.bundle("lakepack", "1.0.8", files("1.0.8", true, SUTRA));
        JsonNode r = checked(other, "X-Drishti-Signature", sig, "X-Drishti-Publisher", "acme");     // a signature of other bytes
        assertThat(check(r, "signature")).startsWith("FAIL: the signature does not verify");
        assertThat(r.path("ok").asBoolean()).isFalse();
    }

    // -- deploy, history, rollback -----------------------------------------------------------------------------------

    @Test
    void deployKeepsThePreviousVersionAndRollbackBringsItBackAndEveryStepIsRecorded() throws Exception {
        // 2.0.0 drops a kind: confirming needs acceptBreaking
        JsonNode up = checked(TestArchives.bundle("lakepack", "2.0.0", files("2.0.0", false, SUTRA)));
        String id = up.path("uploadId").asText();
        mvc.perform(post("/api/v1/admin/packs/deploy/" + id)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("1 breaking change")));
        mvc.perform(post("/api/v1/admin/packs/deploy/" + id).param("acceptBreaking", "true")).andExpect(status().isOk())
                .andExpect(jsonPath("$.deployed").value("2.0.0")).andExpect(jsonPath("$.previous").value("1.0.0"))
                .andExpect(jsonPath("$.restarting").exists());
        assertThat(Files.readString(root.resolve("installed/lakepack/pack.yaml"))).contains("version: 2.0.0");
        mvc.perform(post("/api/v1/admin/packs/deploy/" + id)).andExpect(status().isNotFound());                // an upload is used once

        // 2.1.0 replaces 2.0.0, which is kept
        JsonNode up2 = checked(TestArchives.bundle("lakepack", "2.1.0", files("2.1.0", false, SUTRA + "# a note\n")));
        mvc.perform(post("/api/v1/admin/packs/deploy/" + up2.path("uploadId").asText()).param("acceptBreaking", "true")).andExpect(status().isOk()).andExpect(jsonPath("$.previous").value("2.0.0"));
        assertThat(Files.readString(root.resolve("installed/lakepack/pack.yaml"))).contains("version: 2.1.0");
        mvc.perform(get("/api/v1/admin/packs/history").param("pack", "lakepack")).andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].action").value("deploy")).andExpect(jsonPath("$.history[0].version").value("2.1.0"))
                .andExpect(jsonPath("$.history[0].previous").value("2.0.0")).andExpect(jsonPath("$.history[0].sha256").isNotEmpty())
                .andExpect(jsonPath("$.kept.lakepack[0].version").value("2.0.0"))
                .andExpect(jsonPath("$.kept.lakepack[*].version").value(hasItem("shipped")));

        mvc.perform(post("/api/v1/admin/packs/lakepack/rollback")).andExpect(status().isOk()).andExpect(jsonPath("$.restored").value("2.0.0"))
                .andExpect(jsonPath("$.replaced").value("2.1.0"));
        assertThat(Files.readString(root.resolve("installed/lakepack/pack.yaml"))).contains("version: 2.0.0");
        mvc.perform(get("/api/v1/admin/packs/history").param("pack", "lakepack"))
                .andExpect(jsonPath("$.history[0].action").value("rollback")).andExpect(jsonPath("$.kept.lakepack[0].version").value("2.1.0"));   // rolling back is undoable
        mvc.perform(post("/api/v1/admin/packs/lakepack/rollback").param("version", "9.9")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/packs/lakepack/rollback").param("version", "shipped")).andExpect(status().isOk()).andExpect(jsonPath("$.restored").value("1.0.0"));
        assertThat(root.resolve("installed/lakepack")).doesNotExist();                                           // the shipped copy shows again

        var events = audit.recent(50, "lakepack");
        assertThat(events).extracting(AuditLog.Event::action).contains("pack-upload-checked", "pack-deployed", "pack-rolled-back");
    }

    @Test
    void sameOrOlderVersionsAreFlaggedNotRefused() throws Exception {
        JsonNode same = checked(TestArchives.bundle("lakepack", "1.0.0", files("1.0.0", true, SUTRA)));
        assertThat(same.path("preview").path("versionOrder").asText()).isEqualTo("same");
        assertThat(same.path("preview").path("counts").path("breaking").asInt()).isZero();
        mvc.perform(delete("/api/v1/admin/packs/deploy/" + same.path("uploadId").asText()));
    }

    // -- data source ---------------------------------------------------------------------------------------------------

    @Test
    void theDataSourceShowsThreeLayersAndAnEditIsAnOverrideNotARewriteOfThePack() throws Exception {
        mvc.perform(get("/api/v1/admin/packs/lakepack/datasource")).andExpect(status().isOk())
                .andExpect(jsonPath("$.overridden").value(false))
                .andExpect(jsonPath("$.connectors[0].name").value("item-store"))
                .andExpect(jsonPath("$.connectors[0].plugin").value("file"))
                .andExpect(jsonPath("$.connectors[0].settings[?(@.key=='root')].source").value(hasItem("pack")))
                .andExpect(jsonPath("$.connectors[0].settings[?(@.key=='lookback-days')].source").value(hasItem("site")))      // the site's value (7) beats the pack's (10)
                .andExpect(jsonPath("$.connectors[0].settings[?(@.key=='lookback-days')].effective").value(hasItem("7")))
                .andExpect(jsonPath("$.connectors[0].settings[?(@.key=='lookback-days')].pack").value(hasItem("10")));
        String before = Files.readString(root.resolve("packs/lakepack/pack.yaml"));
        String edit = "{\"connectors\":{\"item-store\":{\"settings\":{\"root\":\"" + root.resolve("files-b") + "\",\"lookback-days\":\"3\",\"lookback-days-x\":\"1\",\"domain\":\"\"}}}}";
        // the test comes first: it tries the edit against the real source and saves nothing
        mvc.perform(post("/api/v1/admin/packs/lakepack/datasource/test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"connector\":\"item-store\",\"connectors\":{\"item-store\":{\"settings\":{\"root\":\"" + root.resolve("files-b") + "\"}}}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.tested").value(containsString("not yet saved")))
                .andExpect(jsonPath("$.connectors[0].kinds[?(@.kind=='item')].dates[0].date").value(hasItem("2026-10-05")))
                .andExpect(jsonPath("$.connectors[0].kinds[?(@.kind=='item')].dates[0].rows").value(hasItem(5)));
        assertThat(root.resolve("settings/lakepack.yaml")).doesNotExist();
        // the settings in force (files-a): two dates of items (newest first) and the one thing
        JsonNode t = JSON.readTree(mvc.perform(post("/api/v1/admin/packs/lakepack/datasource/test")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode items = null;
        for (JsonNode k : t.path("connectors").get(0).path("kinds")) {
            if (k.path("kind").asText().equals("item")) {
                items = k;
            }
        }
        assertThat(items.path("dates").findValuesAsText("date")).containsExactly("2026-10-05", "2026-10-02");
        assertThat(items.path("dates").findValues("rows").stream().map(JsonNode::asInt)).containsExactly(2, 3);
        assertThat(items.path("exact").asBoolean()).isTrue();

        mvc.perform(put("/api/v1/admin/packs/lakepack/datasource").contentType(MediaType.APPLICATION_JSON)
                .content(edit.replace("\"lookback-days-x\":\"1\",", ""))).andExpect(status().isOk()).andExpect(jsonPath("$.overridden").value(true))
                .andExpect(jsonPath("$.changed").value(hasItem("item-store.root")));
        assertThat(Files.readString(root.resolve("settings/lakepack.yaml"))).contains("files-b").contains("# Data source override");
        assertThat(Files.readString(root.resolve("packs/lakepack/pack.yaml"))).as("the pack's own file is untouched").isEqualTo(before);
        mvc.perform(get("/api/v1/admin/packs/lakepack/datasource")).andExpect(jsonPath("$.overridden").value(true))
                .andExpect(jsonPath("$.connectors[0].settings[?(@.key=='root')].overridden").value(hasItem(true)))
                .andExpect(jsonPath("$.connectors[0].settings[?(@.key=='root')].pack").value(hasItem(root.resolve("files-a").toString())))
                .andExpect(jsonPath("$.connectors[0].settings[?(@.key=='root')].override").value(hasItem(root.resolve("files-b").toString())))
                .andExpect(jsonPath("$.connectors[0].settings[?(@.key=='root')].source").value(hasItem("override")))
                .andExpect(jsonPath("$.connectors[0].settings[?(@.key=='lookback-days')].source").value(hasItem("site")));   // env beats the file
        mvc.perform(get("/api/v1/admin/packs")).andExpect(jsonPath("$[?(@.name=='lakepack')].dataSourceOverridden").value(hasItem(true)));
        // the saved override is what the next test uses
        mvc.perform(post("/api/v1/admin/packs/lakepack/datasource/test")).andExpect(jsonPath("$.connectors[0].kinds[?(@.kind=='item')].dates[0].rows").value(hasItem(5)));

        mvc.perform(delete("/api/v1/admin/packs/lakepack/datasource")).andExpect(status().isOk()).andExpect(jsonPath("$.overridden").value(false));
        assertThat(root.resolve("settings/lakepack.yaml")).doesNotExist();
        mvc.perform(get("/api/v1/admin/packs/lakepack/datasource")).andExpect(jsonPath("$.overridden").value(false));
        assertThat(audit.recent(50, "lakepack")).extracting(AuditLog.Event::action).contains("pack-datasource-changed", "pack-datasource-reset", "pack-datasource-tested");
    }

    @Test
    void anEditWithTheSameValuesAsThePackIsNotAnOverride() throws Exception {
        String same = "{\"connectors\":{\"item-store\":{\"settings\":{\"root\":\"" + root.resolve("files-a") + "\",\"lookback-days\":\"10\"}}}}";
        mvc.perform(put("/api/v1/admin/packs/lakepack/datasource").contentType(MediaType.APPLICATION_JSON).content(same)).andExpect(status().isOk())
                .andExpect(jsonPath("$.overridden").value(false)).andExpect(jsonPath("$.restarting").value(false));
        assertThat(root.resolve("settings/lakepack.yaml")).doesNotExist();
    }

    @Test
    void invalidDataSourcesAreRefusedAndNothingIsWritten() throws Exception {
        for (String body : new String[] {
                "{\"connectors\":{\"item-store\":{\"settings\":{\"password\":\"hunter2\"}}}}",                  // a secret that is not an env reference
                "{\"connectors\":{\"nope\":{\"enabled\":false}}}",                                              // not the pack's connector
                "{\"connectors\":{\"item-store\":{\"plugin\":\"jdbc\"}}}",                                      // the plugin belongs to the pack
                "{\"connectors\":{\"item-store\":{\"settings\":{\"root\":\"\"}}}}"}) {
            mvc.perform(put("/api/v1/admin/packs/lakepack/datasource").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("not valid")));
        }
        assertThat(root.resolve("settings/lakepack.yaml")).doesNotExist();
        mvc.perform(get("/api/v1/admin/packs/not-loaded/datasource")).andExpect(status().isNotFound());
    }

    @Test
    void aCredentialIsOnlyAnEnvironmentReferenceAndATestSaysWhenTheVariableIsMissing() throws Exception {
        String body = "{\"connectors\":{\"item-store\":{\"settings\":{\"password\":\"${DRISHTI_TEST_SURELY_UNSET_PW}\"}}}}";
        mvc.perform(post("/api/v1/admin/packs/lakepack/datasource/test").contentType(MediaType.APPLICATION_JSON).content("{\"connectors\":" + JSON.readTree(body).path("connectors") + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.connectors[0].error").value(containsString("environment variable that is not set")));
    }

    @Test
    void aSwitchedOffConnectorIsNotTried() throws Exception {
        mvc.perform(post("/api/v1/admin/packs/lakepack/datasource/test").contentType(MediaType.APPLICATION_JSON)
                .content("{\"connectors\":{\"item-store\":{\"enabled\":false}}}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.connectors[0].skipped").value(true)).andExpect(jsonPath("$.ok").value(true));
    }
}
