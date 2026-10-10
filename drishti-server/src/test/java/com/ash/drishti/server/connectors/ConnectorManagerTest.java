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
package com.ash.drishti.server.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SettingSpec;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import com.ash.drishti.engine.source.ConnectionProbe;
import com.ash.drishti.engine.source.SettingCatalogue;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.packs.ConnectorBootstrap;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.packs.PackRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

/** The connector manager on real files and a registry of fake plugins: reload, precedence, validation, versions, test connection. */
class ConnectorManagerTest {

    /** A plugin with declared settings; it fails to start on url "bad", and with a TLS-looking error on url "tls". */
    public static final class Fake implements SourcePlugin {
        static final List<String> LOG = new CopyOnWriteArrayList<>();
        private String url = "?";

        @Override
        public PluginManifest manifest() {
            return new PluginManifest("fake", "t", Set.of("trade"), SourceCapabilities.FETCH_ONLY);
        }

        @Override
        public List<SettingSpec> settingSpecs() {
            return List.of(new SettingSpec("url", "string", true, null, "Where", false, "connection"),
                    new SettingSpec("password", "string", false, null, "Secret", true, "connection"),
                    new SettingSpec("pool", "int", false, "4", "Pool size", false, "tuning"),
                    new SettingSpec("mode", "enum:a|b", false, null, "Mode", false, "tuning"),
                    new SettingSpec("layout.*", "string", false, null, "Layouts", false, "mapping"));
        }

        @Override
        public void start(SourceContext c) {
            url = c.setting("url", "?");
            if (url.contains("bad")) {
                throw new IllegalStateException("cannot connect to " + url + " with " + c.setting("password", ""));
            }
            if (url.contains("tls")) {
                throw new IllegalStateException("javax.net.ssl.SSLHandshakeException: PKIX path building failed: unable to find valid certification path");
            }
            LOG.add("start:" + c.settings().get("source-name") + ":" + url);
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        @Override
        public void close() {
            LOG.add("close:" + url);
        }
    }

    private Path root;
    private ConnectorFiles files;
    private SourceRegistry registry;
    private ConnectorManager manager;

    private void build(Path dir, String packYaml, String connectorFile, String connectorText, Map<String, SourcesProperties.ConnectorSettings> app) throws Exception {
        root = dir;
        Fake.LOG.clear();
        Path pack = Files.createDirectories(dir.resolve("packs/risk"));
        Files.writeString(pack.resolve("pack.yaml"), packYaml);
        files = new ConnectorFiles(dir.resolve("connectors"));
        if (connectorFile != null) {
            files.write(connectorFile, connectorText);
        }
        MockEnvironment env = new MockEnvironment().withProperty("drishti.packs.dir", dir.resolve("packs").toString())
                .withProperty("drishti.packs.loaded", "risk").withProperty("THE_URL", "db://from-env");
        PackRegistry packs = new PackRegistry(env);
        // as the environment post-processor would at start: templates become files, files become properties
        ConnectorBootstrap.run(packs.packs(), files, null, true);
        var defs = files.readAll(new java.util.HashMap<>());
        var startup = new java.util.LinkedHashMap<String, SourcesProperties.ConnectorSettings>(app);
        defs.forEach((n, d) -> startup.put(n, new SourcesProperties.ConnectorSettings(d.plugin(), d.isEnabled(), d.kinds(), d.settings())));
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(1), null, startup, dir.resolve("connectors").toString(), "poll",
                Duration.ofMillis(50), Duration.ZERO, true);
        SourcePlugin proto = new Fake();
        registry = new SourceRegistry(List.of(proto), props, new JsonCodec());
        ConnectionProbe probe = new ConnectionProbe(List.of(proto), new JsonCodec());
        manager = new ConnectorManager(files, registry, props, packs, env, new SettingCatalogue(List.of(proto)), probe, 2, 5);
    }

    private static final String PACK = "pack: risk\nkinds: [trade]\nroutes:\n  trade: risk-db\nconnectors:\n  risk-db:\n    plugin: fake\n    kinds: [trade]\n"
            + "    settings:\n      url: db://pack-default\n";

    @Test
    void aPackTemplateBecomesAFileAndTheFileIsWhatRuns(@TempDir Path dir) throws Exception {
        build(dir, PACK, null, null, Map.of());
        assertThat(files.exists("risk-db")).isTrue();
        assertThat(registry.plugin("risk-db")).isPresent();
        assertThat(Fake.LOG).containsExactly("start:risk-db:db://pack-default");
        assertThat(manager.origin("risk-db")).isEqualTo("file");
        assertThat(manager.generated()).isEmpty();                                            // (reported by the post-processor in a real start)
        Map<String, Object> d = manager.detail("risk-db");
        assertThat(d).containsEntry("plugin", "fake").containsEntry("state", "RUNNING").containsEntry("template", "risk");
        assertThat(d.get("packDefault").toString()).contains("db://pack-default");
        assertThat(d.get("usedBy").toString()).contains("risk").contains("trade");
    }

    @Test
    void aFileReplacesThePacksTemplateWholesaleAndNothingIsMerged(@TempDir Path dir) throws Exception {
        build(dir, PACK + "      pool: '9'\n", "risk-db", "plugin: fake\nsettings:\n  url: db://site\n", Map.of());
        assertThat(Fake.LOG).containsExactly("start:risk-db:db://site");
        assertThat(files.read("risk-db").settings()).containsOnlyKeys("url");                // the pack's pool is not carried over
    }

    @Test
    void aFileWithNoPackCounterpartIsANewSiteConnector(@TempDir Path dir) throws Exception {
        build(dir, PACK, "extra-feed", "plugin: fake\nkinds: [trade]\nsettings:\n  url: db://extra\n", Map.of());
        assertThat(manager.origin("extra-feed")).isEqualTo("file");
        assertThat(manager.template("extra-feed")).isEmpty();
        assertThat(manager.names()).contains("extra-feed", "risk-db");
        assertThat(registry.plugin("extra-feed")).isPresent();
    }

    @Test
    void applicationYamlConnectorsStillWorkAndAreReportedAsDeprecated(@TempDir Path dir) throws Exception {
        var app = Map.of("legacy", new SourcesProperties.ConnectorSettings("fake", true, List.of(), Map.of("url", "db://legacy")));
        build(dir, PACK, null, null, app);
        assertThat(registry.plugin("legacy")).isPresent();
        assertThat(manager.origin("legacy")).isEqualTo("application");
        // saving creates the file; from then on origin is file
        manager.save("legacy", "plugin: fake\nsettings:\n  url: db://moved\n", null, false);
        assertThat(manager.origin("legacy")).isEqualTo("file");
        assertThat(files.exists("legacy")).isTrue();
    }

    @Test
    void deprecationIsLoggedNamingTheEquivalentFile(@TempDir Path dir) throws Exception {
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("applicationConfig: [classpath:/application.yaml]",
                Map.of("drishti.sources.connectors.legacy.plugin", "fake", "drishti.sources.connectors.legacy.settings.url", "x")));
        var logs = new java.util.ArrayList<String>();
        var appender = new ch.qos.logback.core.AppenderBase<ch.qos.logback.classic.spi.ILoggingEvent>() {
            @Override
            protected void append(ch.qos.logback.classic.spi.ILoggingEvent e) {
                logs.add(e.getFormattedMessage());
            }
        };
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ConnectorManager.class);
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        try {
            Path pack = Files.createDirectories(dir.resolve("packs/risk"));
            Files.writeString(pack.resolve("pack.yaml"), "pack: risk\nkinds: [trade]\n");
            MockEnvironment penv = new MockEnvironment().withProperty("drishti.packs.dir", dir.resolve("packs").toString()).withProperty("drishti.packs.loaded", "risk");
            penv.getPropertySources().addFirst(env.getPropertySources().get("applicationConfig: [classpath:/application.yaml]"));
            var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(1), null, Map.of(), dir.resolve("c").toString(), "off", null, Duration.ZERO, true);
            new ConnectorManager(new ConnectorFiles(dir.resolve("c")), new SourceRegistry(List.of(new Fake()), props, new JsonCodec()), props, new PackRegistry(penv), penv,
                    new SettingCatalogue(List.of(new Fake())), new ConnectionProbe(List.of(new Fake()), new JsonCodec()), 2, 5);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(logs).anyMatch(m -> m.contains("deprecated") && m.contains("'legacy'") && m.contains("legacy.yaml"));
    }

    @Test
    void aChangedFileRestartsOnlyThatConnectorANewFileStartsOneADeletedFileStopsIt(@TempDir Path dir) throws Exception {
        build(dir, PACK, "other", "plugin: fake\nsettings:\n  url: db://other\n", Map.of());
        Fake.LOG.clear();
        files.write("risk-db", "plugin: fake\nsettings:\n  url: db://changed\n");
        assertThat(manager.scan()).containsExactly("risk-db");
        assertThat(Fake.LOG).containsExactly("close:db://pack-default", "start:risk-db:db://changed");   // "other" untouched
        assertThat(manager.scan()).isEmpty();                                                          // nothing more to do

        Fake.LOG.clear();
        files.write("brand-new", "plugin: fake\nsettings:\n  url: db://new\n");
        assertThat(manager.scan()).containsExactly("brand-new");
        assertThat(registry.plugin("brand-new")).isPresent();

        Files.delete(files.file("other"));
        assertThat(manager.scan()).containsExactly("other");
        assertThat(registry.plugin("other")).isEmpty();
        assertThat(Fake.LOG).contains("close:db://other");
    }

    @Test
    void deletingAPackConnectorsFileBringsTheTemplateBack(@TempDir Path dir) throws Exception {
        build(dir, PACK, null, null, Map.of());
        files.write("risk-db", "plugin: fake\nsettings:\n  url: db://site\n");
        manager.scan();
        Files.delete(files.file("risk-db"));
        manager.scan();
        assertThat(registry.applied("risk-db").get().settings()).containsEntry("url", "db://pack-default");
    }

    @Test
    void aBadFileKeepsTheLastGoodConfigurationAndShowsTheProblem(@TempDir Path dir) throws Exception {
        build(dir, PACK, "other", "plugin: fake\nsettings:\n  url: db://good\n", Map.of());
        Fake.LOG.clear();
        files.write("other", "plugin: fake\nbogus: 1\n");
        assertThat(manager.scan()).isEmpty();
        assertThat(Fake.LOG).isEmpty();                                                       // nothing stopped
        assertThat(registry.plugin("other")).isPresent();
        Map<String, Object> s = manager.summary("other");
        assertThat(s.get("problems").toString()).contains("unknown key 'bogus'").contains("last good configuration is running");
        assertThat(manager.healthSummary().get("badFiles").toString()).contains("other");
        assertThat(manager.fileProblems()).containsKey("other");

        files.write("other", "plugin: fake\nsettings:\n  url: ${NOT_SET_ANYWHERE}\n");          // an unset variable is a problem too, not a crash
        manager.scan();
        assertThat(manager.fileProblems().get("other")).contains("environment variable that is not set");
        assertThat(registry.applied("other").get().settings()).containsEntry("url", "db://good");

        files.write("other", "plugin: fake\nsettings:\n  url: ${THE_URL}\n");                 // fixed: the problem clears, placeholders are resolved
        manager.scan();
        assertThat(manager.fileProblems()).doesNotContainKey("other");
        assertThat(registry.applied("other").get().settings()).containsEntry("url", "db://from-env");
    }

    @Test
    void theWatcherPicksUpEditsOnItsOwn(@TempDir Path dir) throws Exception {
        build(dir, PACK, null, null, Map.of());
        manager.startWatching();
        try {
            assertThat(manager.watchState()).isIn("POLLING", "WATCHING");
            files.write("risk-db", "plugin: fake\nsettings:\n  url: db://edited-live\n");
            long end = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < end && !registry.applied("risk-db").get().settings().get("url").equals("db://edited-live")) {
                Thread.sleep(50);
            }
            assertThat(registry.applied("risk-db").get().settings()).containsEntry("url", "db://edited-live");
        } finally {
            manager.stopWatching();
        }
    }

    @Test
    void validationNamesTheProblemPerField(@TempDir Path dir) throws Exception {
        build(dir, PACK, null, null, Map.of());
        assertThatThrownBy(() -> manager.save("Bad_Name", "plugin: fake\n", null, false)).isInstanceOf(DrishtiException.class).hasMessageContaining("not a connector name");
        assertThatThrownBy(() -> manager.save("plugins", "plugin: fake\nsettings: {url: x}\n", null, false)).hasMessageContaining("reserved");
        assertThatThrownBy(() -> manager.save("x", "plugin: nosuch\n", null, false)).hasMessageContaining("no plugin named 'nosuch'").hasMessageContaining("fake");
        assertThatThrownBy(() -> manager.save("x", "plugin: fake\n", null, false)).hasMessageContaining("settings.url: required by the fake plugin");
        assertThatThrownBy(() -> manager.save("x", "plugin: fake\nsettings: {url: u, pool: many}\n", null, false)).hasMessageContaining("settings.pool: a whole number");
        assertThatThrownBy(() -> manager.save("x", "plugin: fake\nsettings: {url: u, mode: z}\n", null, false)).hasMessageContaining("one of a, b");
        assertThatThrownBy(() -> manager.save("x", "name: y\nplugin: fake\n", null, false)).hasMessageContaining("file name is the connector's name");
        assertThatThrownBy(() -> manager.save("x", "plugin: fake\nsettings: {url: 'db://u:hunter2@h/x'}\n", null, false)).hasMessageContaining("password inside a URL");
        // an unknown setting and an unknown kind are warnings (typo hints), not errors
        var w = new java.util.ArrayList<ConnectorManager.Problem>();
        manager.check("x", "plugin: fake\nkinds: [nokind]\nsettings: {url: u, urll: v, layout: {trade: {columns: [a]}}}\n", w);
        assertThat(w).extracting(ConnectorManager.Problem::message).anyMatch(m -> m.contains("'urll' is not a setting the fake plugin reads"))
                .anyMatch(m -> m.contains("kind 'nokind'"));
        assertThat(w).extracting(ConnectorManager.Problem::field).doesNotContain("settings.layout.trade.columns");   // layout.* is declared
    }

    @Test
    void aLiteralCredentialIsRefusedButEnvAndFileReferencesAreFine(@TempDir Path dir) throws Exception {
        build(dir, PACK, null, null, Map.of());
        assertThatThrownBy(() -> manager.save("x", "plugin: fake\nsettings: {url: u, password: hunter2}\n", null, false))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("settings.password").hasMessageContaining("never written into a connector file").hasMessageContaining("${PASSWORD}");
        assertThatThrownBy(() -> manager.save("x", "plugin: fake\nsettings: {url: u, password: '${X:fallback}'}\n", null, false)).hasMessageContaining("never written");
        manager.save("x", "plugin: fake\nsettings: {url: u, password: '${DB_PW}'}\n", null, false);
        manager.save("y", "plugin: fake\nsettings: {url: u, password: 'file:/run/secrets/pw'}\n", null, false);
        assertThat(files.exists("x")).isTrue();
        assertThat(files.exists("y")).isTrue();
        // a hand-edited file with a literal secret still loads (the file watcher is lenient) but is warned about
        assertThat(manager.validateText("z", "plugin: fake\nsettings: {url: u, password: hunter2}\n")).extracting(ConnectorManager.Problem::level).contains("error");
    }

    @Test
    void detailMasksLiteralSecretsFromOtherOriginsAndShowsReferences(@TempDir Path dir) throws Exception {
        var app = Map.of("legacy", new SourcesProperties.ConnectorSettings("fake", true, List.of(), Map.of("url", "db://legacy", "password", "hunter2")));
        build(dir, PACK, "refd", "plugin: fake\nsettings:\n  url: u\n  password: ${DB_PW}\n", app);
        @SuppressWarnings("unchecked")
        Map<String, String> legacy = (Map<String, String>) manager.detail("legacy").get("settings");
        assertThat(legacy).containsEntry("password", "***").containsEntry("url", "db://legacy");
        @SuppressWarnings("unchecked")
        Map<String, String> refd = (Map<String, String>) manager.detail("refd").get("settings");
        assertThat(refd).containsEntry("password", "${DB_PW}");
    }

    @Test
    void anExistingFileNeedsTheEtagAndAStaleOneIsAConflict(@TempDir Path dir) throws Exception {
        build(dir, PACK, "lake", "plugin: fake\nsettings:\n  url: u1\n", Map.of());
        String v1 = (String) manager.detail("lake").get("etag");
        assertThatThrownBy(() -> manager.save("lake", "plugin: fake\nsettings:\n  url: u2\n", null, false))
                .isInstanceOf(DrishtiException.class).hasMessageContaining("already exists").hasMessageContaining("If-Match");
        var c = manager.save("lake", "plugin: fake\nsettings:\n  url: u2\n", v1, false);
        assertThat(c.changed()).containsExactly("url");
        String v2 = (String) c.detail().get("etag");
        assertThat(v2).isNotEqualTo(v1);
        assertThatThrownBy(() -> manager.save("lake", "plugin: fake\nsettings:\n  url: u3\n", v1, false))        // someone else saved in between
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.CONNECTOR_CONFLICT))
                .hasMessageContaining("changed since you read it");
        assertThat(files.read("lake").settings()).containsEntry("url", "u2");
    }

    @Test
    void everySaveKeepsTheEarlierTextAndRestoreBringsItBack(@TempDir Path dir) throws Exception {
        build(dir, PACK, "lake", "plugin: fake\nsettings:\n  url: u1\n", Map.of());
        String v = (String) manager.detail("lake").get("etag");
        var c = manager.save("lake", "plugin: fake\nsettings:\n  url: u2\n", v, false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> history = (List<Map<String, Object>>) manager.detail("lake").get("history");
        assertThat(history).hasSize(1);
        String id = (String) history.get(0).get("id");
        assertThat(manager.revisionText("lake", id)).contains("u1");
        manager.restore("lake", id, (String) c.detail().get("etag"), false);
        assertThat(files.read("lake").settings()).containsEntry("url", "u1");
        assertThat(registry.applied("lake").get().settings()).containsEntry("url", "u1");              // and it is live
        assertThatThrownBy(() -> manager.revisionText("lake", "20200101T000000000")).hasMessageContaining("no kept version");
    }

    @Test
    void disablingOrDeletingAConnectorPacksUseNeedsConfirmation(@TempDir Path dir) throws Exception {
        build(dir, PACK, "solo", "plugin: fake\nsettings:\n  url: u\n", Map.of());
        // risk-db is used by pack risk for kind trade
        assertThatThrownBy(() -> manager.setEnabled("risk-db", false, null, false)).isInstanceOf(DrishtiException.class)
                .hasMessageContaining("pack risk (trade)").hasMessageContaining("confirm=true");
        assertThat(registry.plugin("risk-db")).isPresent();
        manager.setEnabled("risk-db", false, null, true);
        assertThat(registry.plugin("risk-db")).isEmpty();
        assertThat(manager.summary("risk-db")).containsEntry("state", "DISABLED").containsEntry("enabled", false);
        assertThat(files.text("risk-db")).contains("enabled: false").contains("db://pack-default");    // the rest of the file is kept
        manager.setEnabled("risk-db", true, null, false);                                              // switching on never needs confirming
        assertThat(registry.plugin("risk-db")).isPresent();
        assertThat(files.text("risk-db")).doesNotContain("enabled");

        // a connector no pack uses is deleted freely; a pack's connector is reset or disabled, never deleted
        assertThat(manager.delete("solo", null, false).detail()).containsEntry("deleted", true);
        assertThat(registry.plugin("solo")).isEmpty();
        assertThat(files.history("solo")).hasSize(1);
        assertThatThrownBy(() -> manager.delete("risk-db", null, true)).hasMessageContaining("named by pack risk's template").hasMessageContaining("reset");
        assertThatThrownBy(() -> manager.delete("nothing", null, true)).satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.CONNECTOR_NOT_FOUND));
    }

    @Test
    void aFileUsedByAPackThroughARouteIsProtectedToo(@TempDir Path dir) throws Exception {
        build(dir, "pack: risk\nkinds: [trade]\nconnectors: [lake-x]\nroutes:\n  trade: lake-x\n", "lake-x", "plugin: fake\nsettings:\n  url: u\n", Map.of());
        assertThatThrownBy(() -> manager.delete("lake-x", null, false)).hasMessageContaining("pack risk (trade)");
        manager.delete("lake-x", null, true);
        assertThat(registry.plugin("lake-x")).isEmpty();
    }

    @Test
    void resetPutsThePacksDefaultBackAndKeepsWhatWasThere(@TempDir Path dir) throws Exception {
        build(dir, PACK, null, null, Map.of());
        manager.save("risk-db", "plugin: fake\nsettings:\n  url: db://mine\n", (String) manager.detail("risk-db").get("etag"), false);
        manager.reset("risk-db", null);
        assertThat(files.read("risk-db").settings()).containsEntry("url", "db://pack-default");
        assertThat(registry.applied("risk-db").get().settings()).containsEntry("url", "db://pack-default");
        assertThat(files.history("risk-db").stream().map(r -> files.revisionText("risk-db", r.id())).toList()).anyMatch(t -> t.contains("db://mine"));
        assertThatThrownBy(() -> manager.reset("solo-none", null)).hasMessageContaining("no pack default");
    }

    @Test
    void aPackNamingAConnectorNobodyDefinedLoadsAndTheViewSaysSo(@TempDir Path dir) throws Exception {
        build(dir, "pack: risk\nkinds: [trade]\nconnectors: [trading-lake]\nroutes:\n  trade: trading-lake\n", null, null, Map.of());
        assertThat(manager.missingFor(packs().get(0))).containsExactly("trading-lake");
        Map<String, Object> view = manager.packView("risk");
        assertThat(view.get("missing").toString()).contains("trading-lake");
        assertThat(view.get("connectors").toString()).contains("NOT_CONFIGURED").contains("/admin/connectors?new=trading-lake");
        assertThat(manager.suggestion("trading-lake")).isEmpty();
        // once somebody creates it, the pack's view and health agree
        manager.save("trading-lake", "plugin: fake\nsettings:\n  url: u\n", null, false);
        assertThat(manager.missingFor(packs().get(0))).isEmpty();
        assertThat(manager.packView("risk").get("missing").toString()).isEqualTo("[]");
    }

    private List<com.ash.drishti.packs.Pack> packs() {
        MockEnvironment env = new MockEnvironment().withProperty("drishti.packs.dir", root.resolve("packs").toString()).withProperty("drishti.packs.loaded", "risk");
        return new PackRegistry(env).packs();
    }

    @Test
    void testConnectionStartsAThrowawayInstanceAndExplainsFailures(@TempDir Path dir) throws Exception {
        build(dir, PACK, null, null, Map.of());
        Fake.LOG.clear();
        Map<String, Object> ok = manager.test("probe-x", "plugin: fake\nkinds: [trade]\nsettings:\n  url: db://fine\n");
        assertThat(ok).containsEntry("ok", true);
        assertThat(registry.plugin("probe-x")).isEmpty();                                                // nothing running changed
        assertThat(Fake.LOG).contains("start:probe:db://fine", "close:db://fine");

        Map<String, Object> bad = manager.test("probe-x", "plugin: fake\nsettings:\n  url: db://bad\n  password: '${THE_URL}'\n");
        assertThat(bad).containsEntry("ok", false);
        assertThat(bad.get("error").toString()).contains("cannot connect to db://bad").doesNotContain("db://from-env");            // the secret is masked
        assertThat(bad.get("error").toString()).contains("***");

        Map<String, Object> tls = manager.test("probe-x", "plugin: fake\nsettings:\n  url: db://tls\n");
        assertThat(tls).containsEntry("ok", false);
        assertThat(tls.get("hint").toString()).contains("certificate is not trusted").contains("tls.truststore.path");

        assertThat(manager.test("probe-x", "plugin: fake\nsettings:\n  url: ${NOT_SET_ANYWHERE}\n").get("error").toString()).contains("environment variable that is not set");
        assertThat(manager.test("probe-x", "plugin: nosuch\n").get("error").toString()).contains("no plugin named 'nosuch'");
        assertThat(manager.test("probe-x", "plugin: [").get("error").toString()).contains("not valid YAML");
    }

    @Test
    void theFormIsGeneratedFromTheDeclaredSettings(@TempDir Path dir) throws Exception {
        build(dir, PACK, null, null, Map.of());
        Map<String, Object> fake = manager.plugins().stream().filter(p -> "fake".equals(p.get("name"))).findFirst().orElseThrow();
        assertThat(fake).containsEntry("declared", true);
        assertThat(fake.get("settings").toString()).contains("name=url").contains("required=true").contains("secret=true").contains("type=enum:a|b")
                .contains("default=4").contains("group=tuning").contains("name=source-name");   // plus the common ones
    }

    @Test
    void textOfRendersTheFormFieldsAndKeepsTypedYamlAsIs(@TempDir Path dir) throws Exception {
        build(dir, PACK, null, null, Map.of());
        String typed = "# my comment\nplugin: fake\nsettings: {url: u}\n";
        assertThat(manager.textOf("x", Map.of("text", typed), "ann")).isEqualTo(typed);
        String rendered = manager.textOf("x", Map.of("plugin", "fake", "enabled", true, "kinds", List.of("trade"), "settings", Map.of("url", "u", "tls", Map.of("truststore", Map.of("path", "/ca"))), "description", "d"), "ann");
        assertThat(rendered).contains("Written from Admin -> Connectors by ann").contains("plugin: fake").contains("truststore:");
        ConnectorFiles.Definition d = files.parse("x", rendered);
        assertThat(d.settings()).containsEntry("tls.truststore.path", "/ca").containsEntry("url", "u");
    }
}
