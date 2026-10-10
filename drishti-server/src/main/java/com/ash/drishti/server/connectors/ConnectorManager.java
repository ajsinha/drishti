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

import java.util.concurrent.locks.ReentrantLock;
import com.ash.drishti.api.SettingSpec;
import com.ash.drishti.common.ConnectorSecrets;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.ConnectionProbe;
import com.ash.drishti.engine.source.SettingCatalogue;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourcesProperties;
import com.ash.drishti.packs.ConnectorFiles;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackLoader;
import com.ash.drishti.packs.PackRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * Connectors as the site manages them: one file per connector in the connector folder, applied to the running server as
 * the files change. This class is the single place that reads, checks, writes and applies them; the REST controller, the
 * file watcher and the pack views all go through it.
 *
 * <p>A connector's definition comes from, highest first: the server's own configuration ({@code
 * drishti.sources.connectors} in application.yaml, deprecated), its file, and the template of a pack that names it. A
 * file replaces the pack's template for the same name altogether (the settings are not merged); the server writes a file
 * from the template at first start, so the file normally exists and is the one place to edit.
 *
 * <p>Concurrency: files are scanned one pass at a time; each connector is reconfigured under its own lock in the
 * {@link SourceRegistry}, so a slow restart of one never holds up another, and reads that are in flight finish on the old
 * instance before it closes. A file that cannot be used keeps the last good configuration running and is reported.
 */
public final class ConnectorManager {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectorManager.class);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]*}");
    private static final int MAX_VALUE = 2000;

    /** One finding about a definition; {@code level} is {@code error} (refused) or {@code warning} (shown, allowed). */
    public record Problem(String level, String field, String message) {}

    private final ConnectorFiles files;
    private final SourceRegistry registry;
    private final SourcesProperties props;
    private final PackRegistry packs;
    private final ConfigurableEnvironment env;
    private final SettingCatalogue catalogue;
    private final ConnectionProbe probe;
    private final int probeDates;
    private final long probeTimeoutMs;

    /** The etag of each file as last processed (good or bad): a pass acts only on files that differ. */
    private final Map<String, String> seen = new java.util.concurrent.ConcurrentHashMap<>();
    /** What is wrong with a file now; the connector keeps running its last good configuration. */
    private final Map<String, String> fileProblems = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Instant> updated = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> applicationDefined;
    private final List<String> bootstrapMessages;
    private final ReentrantLock scanLock = new ReentrantLock();   // file reads and writes: a ReentrantLock, not synchronized (Java 21 pins virtual threads)
    private volatile DirectoryWatcher watcher;

    public ConnectorManager(ConnectorFiles files, SourceRegistry registry, SourcesProperties props, PackRegistry packs, ConfigurableEnvironment env,
            SettingCatalogue catalogue, ConnectionProbe probe, int probeDates, int probeTimeoutSeconds) {
        this.files = files;
        this.registry = registry;
        this.props = props;
        this.packs = packs;
        this.env = env;
        this.catalogue = catalogue;
        this.probe = probe;
        this.probeDates = probeDates;
        this.probeTimeoutMs = probeTimeoutSeconds * 1000L;
        this.applicationDefined = applicationDefined(env);
        this.bootstrapMessages = indexed(env, "drishti.sources.connectors-bootstrap");
        // what the environment post-processor loaded at start is "seen": only later changes are acted on
        Map<String, String> bad = new LinkedHashMap<>();
        files.readAll(bad).forEach((n, d) -> {
            seen.put(n, d.etag());
            updated.put(n, d.modified());
        });
        bad.forEach((n, m) -> {
            fileProblems.put(n, m);
            String t = files.text(n);
            seen.put(n, t == null ? "" : ConnectorFiles.etag(t));
            LOG.warn("connector file {}.yaml is not usable: {}", n, m);
        });
        files.misnamed().forEach(f -> LOG.warn("{} in {} is not a connector file (names are lower case letters, digits and hyphens, ending .yaml); it is ignored", f, files.dir()));
        bootstrapMessages.forEach(m -> LOG.info("connectors: {}", m));
        warnDeprecated();
    }

    private static List<String> indexed(ConfigurableEnvironment env, String prefix) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            String v = env.getProperty(prefix + "[" + i + "]");
            if (v == null) {
                break;
            }
            out.add(v);
        }
        return out;
    }

    public ConnectorFiles files() {
        return files;
    }

    public SettingCatalogue catalogue() {
        return catalogue;
    }

    /** What the start-up migration and generation did (for the log and the audit trail). */
    public List<String> bootstrapMessages() {
        return bootstrapMessages;
    }

    public List<String> generated() {
        return indexed(env, "drishti.sources.connectors-generated");
    }

    public List<String> migrated() {
        return indexed(env, "drishti.sources.connectors-migrated");
    }

    // -- the server's own configuration (deprecated) ---------------------------------------------------------------

    /** Connector names defined in a property source of the site (application.yaml, a profile, the environment), not in a pack or a file. */
    private static Set<String> applicationDefined(ConfigurableEnvironment env) {
        Set<String> out = new TreeSet<>();
        String prefix = "drishti.sources.connectors.";
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (com.ash.drishti.packs.PackEnvironmentPostProcessor.SOURCE.equals(ps.getName())
                    || com.ash.drishti.packs.PackEnvironmentPostProcessor.FILES_SOURCE.equals(ps.getName())
                    || "configurationProperties".equals(ps.getName()) || !(ps instanceof EnumerablePropertySource<?> eps)) {
                continue;
            }
            for (String key : eps.getPropertyNames()) {
                String k = key.toLowerCase(java.util.Locale.ROOT).replace('_', '-');
                if (key.startsWith(prefix)) {
                    String rest = key.substring(prefix.length());
                    int dot = rest.indexOf('.');
                    out.add(dot < 0 ? rest : rest.substring(0, dot));
                } else if (k.startsWith("drishti-sources-connectors-") && !k.startsWith("drishti-sources-connectors-dir")
                        && !k.startsWith("drishti-sources-connectors-watch") && !k.startsWith("drishti-sources-connectors-poll")
                        && !k.startsWith("drishti-sources-connectors-drain") && !k.startsWith("drishti-sources-connectors-generate")
                        && !k.startsWith("drishti-sources-connectors-bootstrap") && !k.startsWith("drishti-sources-connectors-migrated")) {
                    out.add("(environment variable " + key + ")");
                }
            }
        }
        return out;
    }

    private void warnDeprecated() {
        for (String n : applicationDefined) {
            if (n.startsWith("(")) {
                LOG.warn("deprecated: {} sets a connector from the environment; move it to a connector file in {} (settings: ...)", n.substring(1, n.length() - 1), files.dir());
            } else {
                LOG.warn("deprecated: connector '{}' is defined in the server's own configuration (drishti.sources.connectors.{}); move it to {} "
                        + "and remove it from application.yaml. Until then it overrides that file setting by setting.", n, n, files.file(ConnectorFiles.validName(n) ? n : "name"));
            }
        }
    }

    /** Names defined in the server's own configuration (deprecated). */
    public Set<String> applicationDefined() {
        return applicationDefined;
    }

    // -- watching --------------------------------------------------------------------------------------------------

    /** Starts watching the folder according to {@code drishti.sources.connectors-watch}. */
    public void startWatching() {
        if (watcher != null) {
            return;
        }
        watcher = new DirectoryWatcher(files.dir(), props.connectorsWatch(), props.connectorsPoll().toMillis(), this::scan);
        watcher.start();
    }

    public void stopWatching() {
        DirectoryWatcher w = watcher;
        if (w != null) {
            w.close();
        }
    }

    /** {@code WATCHING}, {@code POLLING}, {@code OFF}, or {@code STOPPED: reason}. */
    public String watchState() {
        return watcher == null ? "OFF" : watcher.state();
    }

    /**
     * One pass over the folder: starts connectors whose file is new, restarts those whose file changed, stops those whose
     * file is gone (a pack's template takes over where there is one). Safe to call from any thread; returns the names acted on.
     */
    public List<String> scan() {
        scanLock.lock();
        try {
            List<String> acted = new ArrayList<>();
            Set<String> present = new LinkedHashSet<>(files.names());
            for (String name : present) {
                String text;
                try {
                    text = files.text(name);
                } catch (RuntimeException e) {
                    problem(name, e.getMessage(), "");
                    continue;
                }
                if (text == null) {
                    continue;
                }
                String etag = ConnectorFiles.etag(text);
                if (!etag.equals(seen.get(name)) && load(name, text, etag)) {
                    acted.add(name);
                }
            }
            for (String name : new ArrayList<>(seen.keySet())) {
                if (!present.contains(name)) {
                    seen.remove(name);
                    fileProblems.remove(name);
                    updated.remove(name);
                    fileGone(name);
                    acted.add(name);
                }
            }
            return acted;
        } finally {
            scanLock.unlock();
        }
    }

    private void problem(String name, String message, String etag) {
        if (!message.equals(fileProblems.put(name, message))) {
            LOG.warn("connector file {}.yaml is not usable; {}: {}", name, registry.applied(name).isPresent() ? "the last good configuration keeps running" : "nothing was started", message);
        }
        seen.put(name, etag);
    }

    private boolean load(String name, String text, String etag) {
        ConnectorFiles.Definition d;
        try {
            d = files.parse(name, text);
        } catch (ConnectorFiles.InvalidFile e) {
            problem(name, e.getMessage(), etag);
            return false;
        }
        SourcesProperties.ConnectorSettings cs;
        try {
            cs = resolved(d);
        } catch (IllegalArgumentException e) {
            problem(name, e.getMessage(), etag);
            return false;
        }
        seen.put(name, etag);
        fileProblems.remove(name);
        updated.put(name, Instant.now());
        var st = registry.apply(name, cs);
        LOG.info("connector {} reconfigured from {}.yaml: {}{}", name, name, st.state(), st.problem() == null ? "" : " (" + st.problem() + ")");
        return true;
    }

    private void fileGone(String name) {
        Optional<Map<String, Object>> template = template(name).map(Template::definition);
        if (template.isPresent()) {
            LOG.info("connector file {}.yaml was removed; the pack's template applies", name);
            registry.apply(name, fromTemplate(template.get()));
        } else {
            LOG.info("connector file {}.yaml was removed; the connector stops", name);
            registry.remove(name);
        }
    }

    /** The settings with environment placeholders resolved; a variable that is not set refuses the file. */
    private SourcesProperties.ConnectorSettings resolved(ConnectorFiles.Definition d) {
        Map<String, String> settings = new LinkedHashMap<>();
        d.settings().forEach((k, v) -> settings.put(k, resolve(d.name(), k, v)));
        String enabled = resolve(d.name(), "enabled", d.enabled());
        List<String> kinds = new ArrayList<>();
        d.kinds().forEach(k -> kinds.add(resolve(d.name(), "kinds", k)));
        return new SourcesProperties.ConnectorSettings(d.plugin(), Boolean.parseBoolean(enabled.trim()), kinds, settings);
    }

    private String resolve(String name, String key, String raw) {
        try {
            return env.resolveRequiredPlaceholders(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(name + ".yaml: '" + key + "' refers to an environment variable that is not set (" + raw + ")");
        }
    }

    // -- templates -------------------------------------------------------------------------------------------------

    /** A pack's suggested definition of a connector. */
    public record Template(String pack, Map<String, Object> definition) {}

    /** The template for a connector name: the most specific pack that offers one. */
    public Optional<Template> template(String name) {
        for (String pn : PackLoader.lineage(packs.packs()).order()) {
            for (Pack p : packs.packs()) {
                if (p.name().equals(pn) && p.connectorTemplates().containsKey(name)) {
                    return Optional.of(new Template(p.name(), p.connectorTemplates().get(name)));
                }
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private SourcesProperties.ConnectorSettings fromTemplate(Map<String, Object> t) {
        Map<String, Object> flat = new LinkedHashMap<>();
        PackLoader.flatten("", t.get("settings") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of(), flat);
        Map<String, String> settings = new LinkedHashMap<>();
        flat.forEach((k, v) -> settings.put(k, env.resolvePlaceholders(String.valueOf(v))));
        List<String> kinds = new ArrayList<>();
        if (t.get("kinds") instanceof List<?> l) {
            l.forEach(x -> kinds.add(String.valueOf(x)));
        }
        String en = t.get("enabled") == null ? "true" : env.resolvePlaceholders(String.valueOf(t.get("enabled")));
        return new SourcesProperties.ConnectorSettings(String.valueOf(t.get("plugin")), Boolean.parseBoolean(en.trim()), kinds, settings);
    }

    @SuppressWarnings("unchecked")
    private String templateText(Template t) {
        Map<String, Object> d = t.definition();
        Map<String, Object> flat = new LinkedHashMap<>();
        PackLoader.flatten("", d.get("settings") instanceof Map<?, ?> s ? (Map<String, Object>) s : Map.of(), flat);
        List<String> kinds = new ArrayList<>();
        if (d.get("kinds") instanceof List<?> l) {
            l.forEach(x -> kinds.add(String.valueOf(x)));
        }
        return files.render("# The default suggested by pack '" + t.pack() + "'.", String.valueOf(d.get("plugin")), d.get("enabled"), kinds,
                d.get("description") == null ? "" : String.valueOf(d.get("description")), flat);
    }

    // -- reading ---------------------------------------------------------------------------------------------------

    /** Every connector name the server knows: files, pack templates, the server's own configuration, and what runs. */
    public Set<String> names() {
        Set<String> out = new TreeSet<>(files.names());
        out.addAll(fileProblems.keySet());
        out.addAll(props.connectors().keySet());
        out.addAll(registry.connectorStatuses().keySet());
        for (Pack p : packs.packs()) {
            out.addAll(p.connectorTemplates().keySet());
        }
        applicationDefined.stream().filter(n -> !n.startsWith("(")).forEach(out::add);
        return out;
    }

    /** True when something defines this connector (a file, a template, the server's configuration). */
    public boolean defined(String name) {
        return files.exists(name) || fileProblems.containsKey(name) || props.connectors().containsKey(name)
                || registry.connectorStatuses().containsKey(name) || template(name).isPresent();
    }

    public String origin(String name) {
        if (files.exists(name) || fileProblems.containsKey(name)) {
            return "file";
        }
        if (template(name).isPresent()) {
            return "pack";
        }
        return "application";
    }

    /** Packs that name the connector and the kinds each sends to it. */
    public List<Map<String, Object>> usedBy(String name) {
        List<Map<String, Object>> out = new ArrayList<>();
        ConnectorFiles.Definition d = safeRead(name);
        for (Pack p : packs.packs()) {
            if (!p.connectorRefs().contains(name)) {
                continue;
            }
            Set<String> kinds = new TreeSet<>();
            p.routes().forEach((kind, c) -> {
                if (c.equals(name)) {
                    kinds.add(kind);
                }
            });
            Map<String, Object> t = p.connectorTemplates().get(name);
            if (t != null && t.get("kinds") instanceof List<?> l) {
                l.forEach(x -> kinds.add(String.valueOf(x)));
            }
            if (d != null) {
                d.kinds().stream().filter(k -> p.kinds().contains(k)).forEach(kinds::add);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("pack", p.name());
            row.put("kinds", List.copyOf(kinds));
            out.add(row);
        }
        return out;
    }

    private ConnectorFiles.Definition safeRead(String name) {
        try {
            return files.read(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The connector names a pack refers to for which nothing is defined (the pack loads; those kinds report it). */
    public List<String> missingFor(Pack pack) {
        return pack.connectorRefs().stream().filter(n -> !defined(n)).toList();
    }

    /** What a pack's template would create, by name: for the pre-filled New connector form. */
    public Optional<Map<String, Object>> suggestion(String name) {
        return template(name).map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pack", t.pack());
            m.put("text", templateText(t));
            return m;
        });
    }

    /** A row of the list. */
    public Map<String, Object> summary(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        ConnectorFiles.Definition d = safeRead(name);
        Optional<SourcesProperties.ConnectorSettings> live = registry.applied(name);
        Optional<SourcesProperties.ConnectorSettings> startup = Optional.ofNullable(props.connectors().get(name));
        Optional<Template> t = template(name);
        String plugin = d != null ? d.plugin() : live.map(SourcesProperties.ConnectorSettings::plugin).orElse(startup.map(SourcesProperties.ConnectorSettings::plugin)
                .orElse(t.map(x -> String.valueOf(x.definition().get("plugin"))).orElse(null)));
        List<String> kinds = d != null ? d.kinds() : live.map(SourcesProperties.ConnectorSettings::kinds).orElse(startup.map(SourcesProperties.ConnectorSettings::kinds).orElse(List.of()));
        m.put("name", name);
        m.put("origin", origin(name));
        m.put("plugin", plugin);
        m.put("kinds", kinds);
        m.put("description", d == null ? "" : d.description());
        var status = registry.connectorStatuses().get(name);
        boolean enabled = d != null ? d.isEnabled() : live.map(SourcesProperties.ConnectorSettings::enabled).orElse(startup.map(SourcesProperties.ConnectorSettings::enabled).orElse(true));
        m.put("enabled", enabled);
        m.put("state", status == null ? (fileProblems.containsKey(name) ? "NOT_LOADED" : enabled ? "NOT_LOADED" : "DISABLED") : status.state());
        String health = null;
        Instant last = null;
        var plugin1 = registry.plugin(name);
        if (plugin1.isPresent()) {
            try {
                health = plugin1.get().health();
            } catch (RuntimeException e) {
                health = "DOWN: " + e.getMessage();
            }
            last = registry.freshness(name).lastUpdate();
        }
        m.put("health", health);
        m.put("lastUpdate", last);
        m.put("updated", updated.get(name));
        m.put("etag", d == null ? null : d.etag());
        m.put("problems", problemsOf(name, status));
        m.put("usedBy", usedBy(name));
        m.put("template", t.map(Template::pack).orElse(null));
        return m;
    }

    private List<String> problemsOf(String name, SourceRegistry.ConnectorStatus status) {
        List<String> out = new ArrayList<>();
        if (fileProblems.containsKey(name)) {
            out.add(fileProblems.get(name) + (registry.applied(name).isPresent() ? " (the last good configuration is running)" : ""));
        }
        if (status != null && status.problem() != null && !"RUNNING".equals(status.state()) && !"DISABLED".equals(status.state())) {
            out.add(status.state().equals("IDLE") ? "not configured: " + status.problem() : status.problem());
        }
        if (applicationDefined.contains(name)) {
            if (files.exists(name) || fileProblems.containsKey(name)) {
                out.add("also defined in the server's own configuration (deprecated); its values override this file after a restart");
            } else {
                out.add("defined in application.yaml (deprecated); save it to create " + name + ".yaml");
            }
        }
        return out;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String n : names()) {
            out.add(summary(n));
        }
        return out;
    }

    /** Folder level problems: files that are not connector files by name. */
    public List<String> misnamed() {
        return files.misnamed();
    }

    /** The detail of one connector: effective settings with credentials masked, the file text, version and usage. */
    public Map<String, Object> detail(String name) {
        if (!defined(name)) {
            throw new DrishtiException(ErrorCode.CONNECTOR_NOT_FOUND, "no connector named '" + name + "'");
        }
        Map<String, Object> m = new LinkedHashMap<>(summary(name));
        ConnectorFiles.Definition d = safeRead(name);
        Map<String, String> settings;
        if (d != null) {
            settings = d.settings();
        } else {
            settings = registry.applied(name).map(SourcesProperties.ConnectorSettings::settings).orElse(
                    Optional.ofNullable(props.connectors().get(name)).map(SourcesProperties.ConnectorSettings::settings).orElse(Map.of()));
        }
        Map<String, String> shown = new LinkedHashMap<>();
        settings.forEach((k, v) -> shown.put(k, ConnectorSecrets.secretKey(k) && !v.isBlank() && !ConnectorSecrets.reference(v) ? "***" : v));
        m.put("settings", shown);
        m.put("text", files.text(name));
        if (files.exists(name)) {
            m.put("file", files.file(name).toString());
        }
        m.put("directory", files.dir().toString());
        template(name).ifPresent(t -> m.put("packDefault", templateText(t)));
        m.put("history", files.exists(name) || !files.history(name).isEmpty() ? files.history(name).stream().map(r -> {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("id", r.id());
            h.put("at", r.at());
            h.put("bytes", r.bytes());
            return h;
        }).toList() : List.of());
        return m;
    }

    /** The connectors a pack names, with the state of each and where to edit it; those nothing defines are listed as missing. */
    public Map<String, Object> packView(String packName) {
        Pack pack = packs.packs().stream().filter(p -> p.name().equals(packName)).findFirst().orElseThrow(
                () -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "'" + packName + "' is not a loaded pack (load it first: Admin -> Packs -> Load)"));
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String n : pack.connectorRefs()) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (defined(n)) {
                row.putAll(summary(n));
                row.put("defined", true);
            } else {
                row.put("name", n);
                row.put("defined", false);
                row.put("state", "NOT_CONFIGURED");
                row.put("problems", List.of("connector " + n + " is not configured"));
                missing.add(n);
            }
            row.put("kinds", pack.routes().entrySet().stream().filter(e -> e.getValue().equals(n)).map(Map.Entry::getKey).toList());
            row.put("editUrl", "/admin/connectors?name=" + n);
            row.put("createUrl", "/admin/connectors?new=" + n);
            row.put("hasTemplate", pack.connectorTemplates().containsKey(n));
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack", packName);
        out.put("connectors", rows);
        out.put("missing", missing);
        out.put("directory", files.dir().toString());
        return out;
    }

    // -- plugins ---------------------------------------------------------------------------------------------------

    public List<Map<String, Object>> plugins() {
        List<Map<String, Object>> out = new ArrayList<>();
        catalogue.all().forEach((name, spec) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("tls", spec.tls());
            m.put("declared", spec.declared());
            m.put("settings", spec.settings().stream().map(s -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("name", s.name());
                x.put("type", s.type());
                x.put("required", s.required());
                x.put("default", s.defaultValue());
                x.put("description", s.description());
                x.put("secret", s.secret() || ConnectorSecrets.secretKey(s.name()));
                x.put("group", s.group());
                return x;
            }).toList());
            out.add(m);
        });
        return out;
    }

    // -- validation ------------------------------------------------------------------------------------------------

    /** Checks a definition as the form or the file would save it. {@code strict} refuses literal credentials. */
    public List<Problem> validate(ConnectorFiles.Definition d, boolean strict) {
        List<Problem> out = new ArrayList<>();
        var spec = catalogue.of(d.plugin());
        if (spec == null) {
            out.add(new Problem("error", "plugin", "no plugin named '" + d.plugin() + "' (installed: " + String.join(", ", new TreeSet<>(catalogue.all().keySet())) + ")"));
        }
        Set<String> known = new TreeSet<>();
        packs.packs().forEach(p -> known.addAll(p.kinds()));
        for (String k : d.kinds()) {
            if (!known.isEmpty() && !known.contains(k)) {
                out.add(new Problem("warning", "kinds", "kind '" + k + "' is not owned by a loaded pack (loaded: " + String.join(", ", known.stream().limit(8).toList()) + (known.size() > 8 ? ", and " + (known.size() - 8) + " more" : "") + ")"));
            }
        }
        boolean on = d.isEnabled();
        for (Map.Entry<String, String> e : d.settings().entrySet()) {
            String key = e.getKey();
            String v = e.getValue();
            String where = "settings." + key;
            if (v.length() > MAX_VALUE || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
                out.add(new Problem("error", where, "one line of at most " + MAX_VALUE + " characters"));
                continue;
            }
            SettingSpec s = spec == null ? null : spec.find(key);
            boolean secret = s != null ? s.secret() : ConnectorSecrets.secretKey(key);
            if (s != null && !s.secret() && ConnectorSecrets.secretKey(key)) {
                secret = true;
            }
            if (secret && !v.isEmpty() && !ConnectorSecrets.reference(v)) {
                out.add(new Problem(strict ? "error" : "warning", where, "a credential is never written into a connector file; use an environment reference such as ${"
                        + key.replaceAll("[^A-Za-z0-9]+", "_").toUpperCase(java.util.Locale.ROOT) + "} or a file: reference such as file:/run/secrets/" + key));
            } else if (!secret && ConnectorSecrets.urlPassword(v)) {
                out.add(new Problem(strict ? "error" : "warning", where, "a password inside a URL is never written into a connector file; give it as a separate setting that is a ${ENV} reference"));
            }
            if (s != null && !PLACEHOLDER.matcher(v).find()) {
                String t = s.type();
                if ("int".equals(t) && !v.matches("-?\\d+")) {
                    out.add(new Problem("error", where, "a whole number"));
                } else if ("boolean".equals(t) && !v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                    out.add(new Problem("error", where, "true or false"));
                } else if (t.startsWith("enum:") && !List.of(t.substring(5).split("\\|")).contains(v)) {
                    out.add(new Problem("error", where, "one of " + t.substring(5).replace("|", ", ")));
                }
            }
            if (spec != null && spec.declared() && s == null) {
                out.add(new Problem("warning", where, "'" + key + "' is not a setting the " + d.plugin() + " plugin reads (a typo?)"));
            }
        }
        if (on && spec != null && spec.declared()) {
            for (SettingSpec s : spec.settings()) {
                if (s.required() && !s.name().endsWith(".*") && s.defaultValue() == null
                        && (d.settings().get(s.name()) == null || d.settings().get(s.name()).isBlank())) {
                    out.add(new Problem("error", "settings." + s.name(), "required by the " + d.plugin() + " plugin"));
                }
            }
        }
        return out;
    }

    private static String errors(List<Problem> ps) {
        return String.join("; ", ps.stream().filter(p -> "error".equals(p.level())).map(p -> p.field() + ": " + p.message()).toList());
    }

    /** Turns the body of a PUT or a test into the file text: either the YAML as typed, or the form's fields rendered. */
    @SuppressWarnings("unchecked")
    public String textOf(String name, Map<String, Object> body, String user) {
        if (body.get("text") instanceof String t) {
            return t;
        }
        List<String> kinds = new ArrayList<>();
        if (body.get("kinds") instanceof List<?> l) {
            l.forEach(x -> kinds.add(String.valueOf(x)));
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        if (body.get("settings") instanceof Map<?, ?> s) {
            PackLoader.flatten("", (Map<String, Object>) s, flat);
        }
        flat.values().removeIf(v -> v == null || String.valueOf(v).isEmpty());
        if (body.get("plugin") == null) {
            throw new DrishtiException(ErrorCode.CONNECTOR_INVALID, "'plugin' is required");
        }
        return files.render("# Written from Admin -> Connectors by " + user + " on " + java.time.LocalDate.now(java.time.ZoneOffset.UTC) + ".", String.valueOf(body.get("plugin")),
                body.get("enabled"), kinds, body.get("description") == null ? "" : String.valueOf(body.get("description")), flat);
    }

    /** Parses a text as the named connector's file and validates it; throws DRS-5031 with every error. */
    public ConnectorFiles.Definition check(String name, String text, List<Problem> warningsOut) {
        if (!ConnectorFiles.validName(name)) {
            throw new DrishtiException(ErrorCode.CONNECTOR_INVALID, "'" + name + "' is not a connector name: lower case letters, digits and hyphens, starting with a letter or digit"
                    + ("plugins".equals(name) || "history".equals(name) ? " ('" + name + "' is reserved)" : ""));
        }
        ConnectorFiles.Definition d;
        try {
            d = files.parse(name, text);
        } catch (ConnectorFiles.InvalidFile e) {
            throw new DrishtiException(ErrorCode.CONNECTOR_INVALID, e.getMessage());
        }
        List<Problem> ps = validate(d, true);
        String errs = errors(ps);
        if (!errs.isEmpty()) {
            throw new DrishtiException(ErrorCode.CONNECTOR_INVALID, "the connector is not valid: " + errs);
        }
        if (warningsOut != null) {
            ps.stream().filter(p -> "warning".equals(p.level())).forEach(warningsOut::add);
        }
        return d;
    }

    /** The kinds the loaded packs own, for the kinds picker. */
    public List<String> knownKinds() {
        Set<String> known = new TreeSet<>();
        packs.packs().forEach(p -> known.addAll(p.kinds()));
        return List.copyOf(known);
    }

    /** A YAML text as the form's fields, plus its findings (a text that cannot be read comes back as one error). */
    public Map<String, Object> parseDraft(String name, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            ConnectorFiles.Definition d = files.parse(ConnectorFiles.validName(name) ? name : "draft", text);
            m.put("plugin", d.plugin());
            m.put("enabled", d.isEnabled());
            m.put("kinds", d.kinds());
            m.put("description", d.description());
            m.put("settings", d.settings());
            m.put("problems", validate(d, true));
        } catch (ConnectorFiles.InvalidFile e) {
            m.put("problems", List.of(new Problem("error", "yaml", e.getMessage())));
        }
        return m;
    }

    /** Structured findings for a draft, without saving anything (errors and warnings). */
    public List<Problem> validateText(String name, String text) {
        if (!ConnectorFiles.validName(name)) {
            return List.of(new Problem("error", "name", "lower case letters, digits and hyphens, starting with a letter or digit"));
        }
        try {
            return validate(files.parse(name, text), true);
        } catch (ConnectorFiles.InvalidFile e) {
            return List.of(new Problem("error", "yaml", e.getMessage()));
        }
    }

    // -- changing --------------------------------------------------------------------------------------------------

    /** The outcome of a change: the new detail, the setting names that changed, and what the server did. */
    public record Change(Map<String, Object> detail, List<String> changed, List<Problem> warnings) {}

    private void requireUnused(String name, String what, boolean confirm) {
        List<Map<String, Object>> used = usedBy(name);
        if (!used.isEmpty() && !confirm) {
            throw new DrishtiException(ErrorCode.CONNECTOR_CONFLICT, what + " connector '" + name + "' would stop serving " + usageText(used)
                    + "; repeat with confirm=true to go ahead");
        }
    }

    public static String usageText(List<Map<String, Object>> used) {
        List<String> parts = new ArrayList<>();
        for (Map<String, Object> u : used) {
            @SuppressWarnings("unchecked")
            List<String> kinds = (List<String>) u.get("kinds");
            parts.add("pack " + u.get("pack") + (kinds.isEmpty() ? "" : " (" + String.join(", ", kinds) + ")"));
        }
        return String.join("; ", parts);
    }

    /**
     * Saves a connector's file. An existing file needs the etag the caller read ({@code ifMatch}); switching off a connector
     * that packs use needs {@code confirm}. The change is applied at once and its outcome is in the returned detail.
     */
    public Change save(String name, String text, String ifMatch, boolean confirm) {
        List<Problem> warnings = new ArrayList<>();
        ConnectorFiles.Definition next = check(name, text, warnings);
        scanLock.lock();
        try {
            String current = files.exists(name) ? ConnectorFiles.etag(files.text(name)) : null;
            if (current != null && (ifMatch == null || !ifMatch.equals(current))) {
                throw new DrishtiException(ErrorCode.CONNECTOR_CONFLICT, ifMatch == null
                        ? "connector '" + name + "' already exists; send the etag you read as If-Match to change it"
                        : "connector '" + name + "' changed since you read it (now " + current + ", you have " + ifMatch + "); reload it and apply your edit again");
            }
            ConnectorFiles.Definition before = safeRead(name);
            if (before != null && before.isEnabled() && !next.isEnabled()) {
                requireUnused(name, "disabling", confirm);
            }
            List<String> changed = diff(before, next);
            files.write(name, text);
            scan();
            return new Change(detail(name), changed, warnings);
        } finally {
            scanLock.unlock();
        }
    }

    /** Switches a connector on or off, keeping the rest of its text. A connector with no file gets one. */
    public Change setEnabled(String name, boolean enabled, String ifMatch, boolean confirm) {
        if (!defined(name)) {
            throw new DrishtiException(ErrorCode.CONNECTOR_NOT_FOUND, "no connector named '" + name + "'");
        }
        String text;
        String tag;
        scanLock.lock();
        try {
            text = files.text(name);
            if (text == null) {
                text = baseText(name);
                tag = null;
            } else {
                tag = ConnectorFiles.etag(text);
                if (ifMatch != null && !ifMatch.equals(tag)) {
                    throw new DrishtiException(ErrorCode.CONNECTOR_CONFLICT, "connector '" + name + "' changed since you read it; reload it");
                }
            }
        } finally {
            scanLock.unlock();
        }
        String edited = withEnabled(text, enabled);
        return save(name, edited, tag, confirm);
    }

    /** Text of a connector that has no file yet: its pack template, or what runs from the server's own configuration. */
    private String baseText(String name) {
        Optional<Template> t = template(name);
        if (t.isPresent()) {
            return templateText(t.get());
        }
        SourcesProperties.ConnectorSettings cs = registry.applied(name).orElse(props.connectors().get(name));
        if (cs == null) {
            throw new DrishtiException(ErrorCode.CONNECTOR_NOT_FOUND, "no connector named '" + name + "'");
        }
        return files.render("# Created from a definition in the server's own configuration.", cs.plugin(), cs.enabled(), cs.kinds(), "", cs.settings());
    }

    static String withEnabled(String text, boolean enabled) {
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        boolean inserted = false;
        for (String line : lines) {
            if (line.matches("^enabled\\s*:.*")) {
                continue;
            }
            out.add(line);
            if (!inserted && line.matches("^plugin\\s*:.*")) {
                if (!enabled) {
                    out.add("enabled: false");
                }
                inserted = true;
            }
        }
        return String.join("\n", out);
    }

    /** Removes a connector's file. A connector a pack's template names is reset, not deleted. */
    public Change delete(String name, String ifMatch, boolean confirm) {
        scanLock.lock();
        try {
            if (!files.exists(name) && !fileProblems.containsKey(name)) {
                if (defined(name)) {
                    throw new DrishtiException(ErrorCode.CONNECTOR_CONFLICT, "connector '" + name + "' comes from "
                            + (template(name).isPresent() ? "a pack" : "the server's configuration") + " and has no file to delete; disable it instead");
                }
                throw new DrishtiException(ErrorCode.CONNECTOR_NOT_FOUND, "no connector named '" + name + "'");
            }
            Optional<Template> t = template(name);
            if (t.isPresent()) {
                throw new DrishtiException(ErrorCode.CONNECTOR_CONFLICT, "connector '" + name + "' is named by pack " + t.get().pack()
                        + "'s template, so it is not deleted: reset it to the pack's default, or disable it");
            }
            String text = files.text(name);
            if (ifMatch != null && text != null && !ifMatch.equals(ConnectorFiles.etag(text))) {
                throw new DrishtiException(ErrorCode.CONNECTOR_CONFLICT, "connector '" + name + "' changed since you read it; reload it");
            }
            requireUnused(name, "deleting", confirm);
            files.delete(name);
            scan();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("deleted", true);
            return new Change(out, List.of(), List.of());
        } finally {
            scanLock.unlock();
        }
    }

    /** Puts a pack's template back as the connector's file. */
    public Change reset(String name, String ifMatch) {
        Template t = template(name).orElseThrow(() -> new DrishtiException(ErrorCode.CONNECTOR_CONFLICT,
                "connector '" + name + "' has no pack default to go back to; delete it, or edit it"));
        return save(name, templateText(t), files.exists(name) ? ifMatch == null ? ConnectorFiles.etag(files.text(name)) : ifMatch : null, true);
    }

    /** Puts a kept earlier text back. */
    public Change restore(String name, String id, String ifMatch, boolean confirm) {
        String text = files.revisionText(name, id);
        if (text == null) {
            throw new DrishtiException(ErrorCode.CONNECTOR_NOT_FOUND, "no kept version " + id + " of connector '" + name + "'");
        }
        String current = files.exists(name) ? ConnectorFiles.etag(files.text(name)) : null;
        return save(name, text, current == null ? null : ifMatch == null ? current : ifMatch, confirm);
    }

    /** The text of a kept earlier version. */
    public String revisionText(String name, String id) {
        String t = files.revisionText(name, id);
        if (t == null) {
            throw new DrishtiException(ErrorCode.CONNECTOR_NOT_FOUND, "no kept version " + id + " of connector '" + name + "'");
        }
        return t;
    }

    /** Writes a file for a connector from a pack's suggested definition if none exists; returns whether it did. */
    public boolean createFromTemplate(String name) {
        scanLock.lock();
        try {
            if (files.exists(name)) {
                return false;
            }
            Template t = template(name).orElse(null);
            if (t == null) {
                return false;
            }
            files.write(name, templateText(t));
            scan();
            return true;
        } finally {
            scanLock.unlock();
        }
    }

    private static List<String> diff(ConnectorFiles.Definition a, ConnectorFiles.Definition b) {
        List<String> out = new ArrayList<>();
        if (a == null) {
            out.add("(new connector)");
            out.addAll(b.settings().keySet());
            return out;
        }
        if (!a.plugin().equals(b.plugin())) {
            out.add("plugin");
        }
        if (!a.enabled().equals(b.enabled())) {
            out.add("enabled");
        }
        if (!a.kinds().equals(b.kinds())) {
            out.add("kinds");
        }
        Set<String> keys = new TreeSet<>(a.settings().keySet());
        keys.addAll(b.settings().keySet());
        for (String k : keys) {
            if (!java.util.Objects.equals(a.settings().get(k), b.settings().get(k))) {
                out.add(k);
            }
        }
        return out;
    }

    // -- testing ---------------------------------------------------------------------------------------------------

    /** Starts a throwaway instance on the text's settings, reads from it, and reports; nothing running is touched. */
    public Map<String, Object> test(String name, String text) {
        ConnectorFiles.Definition d;
        try {
            d = files.parse(name, text);
        } catch (ConnectorFiles.InvalidFile e) {
            return failure(name, "the connector is not valid: " + e.getMessage(), null);
        }
        List<Problem> ps = validate(d, false);
        String errs = errors(ps);
        if (!errs.isEmpty()) {
            return failure(name, "the connector is not valid: " + errs, d.plugin());
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : d.settings().entrySet()) {
            try {
                resolved.put(e.getKey(), env.resolveRequiredPlaceholders(e.getValue()));
            } catch (IllegalArgumentException x) {
                return failure(name, "setting '" + e.getKey() + "' refers to an environment variable that is not set: " + e.getValue(), d.plugin());
            }
        }
        Map<String, String> read;
        try {
            read = ConnectorSecrets.resolve(resolved);
        } catch (IllegalStateException x) {
            return failure(name, x.getMessage(), d.plugin());
        }
        List<String> secrets = new ArrayList<>();
        read.forEach((k, v) -> {
            if (ConnectorSecrets.secretKey(k) && !v.isBlank()) {
                secrets.add(v);
            }
        });
        List<String> kinds = d.kinds().isEmpty() ? usedBy(name).stream().flatMap(u -> {
            @SuppressWarnings("unchecked")
            List<String> ks = (List<String>) u.get("kinds");
            return ks.stream();
        }).distinct().toList() : d.kinds();
        ConnectionProbe.Result r = probe.probe(d.plugin(), kinds, read, probeDates, probeTimeoutMs);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connector", name);
        out.put("plugin", d.plugin());
        out.put("ok", r.ok());
        out.put("health", r.health());
        out.put("ms", r.ms());
        String err = r.error();
        for (String s : secrets) {
            err = err == null ? null : err.replace(s, "***");
        }
        out.put("error", err);
        out.put("hint", err == null ? null : hint(err));
        List<Map<String, Object>> ks = new ArrayList<>();
        for (ConnectionProbe.KindRows k : r.kinds()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", k.kind());
            m.put("exact", k.exact());
            m.put("note", k.note());
            m.put("dates", k.dates().stream().map(x -> {
                Map<String, Object> y = new LinkedHashMap<>();
                y.put("date", x.date() == null ? null : x.date().toString());
                y.put("rows", x.rows());
                return y;
            }).toList());
            ks.add(m);
        }
        out.put("kinds", ks);
        out.put("warnings", ps.stream().filter(p -> "warning".equals(p.level())).map(p -> p.field() + ": " + p.message()).toList());
        return out;
    }

    private static Map<String, Object> failure(String name, String message, String plugin) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connector", name);
        out.put("plugin", plugin);
        out.put("ok", false);
        out.put("health", "DOWN");
        out.put("ms", 0);
        out.put("error", message);
        out.put("hint", null);
        out.put("kinds", List.of());
        out.put("warnings", List.of());
        return out;
    }

    /** A plain-words reading of the errors people meet with TLS and connections. */
    static String hint(String error) {
        String e = error.toLowerCase(java.util.Locale.ROOT);
        if (e.contains("pkix path building failed") || e.contains("unable to find valid certification path") || e.contains("unknown ca")
                || e.contains("certificate_unknown") || e.contains("self-signed") || e.contains("self signed")) {
            return "The server's certificate is not trusted. Put the certificate authority that signed it in tls.ca-file (a PEM file) "
                    + "or tls.truststore (a PKCS12 or JKS store with tls.truststore-password).";
        }
        if (e.contains("no subject alternative names") || e.contains("hostname") && e.contains("verif") || e.contains("certificate doesn't match")) {
            return "The certificate does not name the host you connect to. Connect using a name the certificate carries, or have a certificate issued for this name.";
        }
        if (e.contains("certificate expired") || e.contains("notafter") || e.contains("certificateexpired")) {
            return "The server's certificate has expired; it needs renewing on the server.";
        }
        if (e.contains("keystore was tampered") || e.contains("password was incorrect") || e.contains("wrong password")) {
            return "The truststore or keystore could not be opened with that password. Check the environment variable or file the password reference points to.";
        }
        if (e.contains("handshake") || e.contains("ssl") || e.contains("tls")) {
            return "The TLS handshake failed. Check that the port speaks TLS, that the protocol versions match, and that tls.* points at the right stores.";
        }
        if (e.contains("connection refused")) {
            return "Nothing is listening at that address and port, or a firewall refuses the connection.";
        }
        if (e.contains("unknownhost") || e.contains("unknown host") || e.contains("nodename nor servname")) {
            return "The host name does not resolve from this server.";
        }
        if (e.contains("timed out") || e.contains("timeout") || e.contains("no answer within")) {
            return "No answer in time. The host may be unreachable from this server, or the service is slow to start.";
        }
        if (e.contains("authentication") || e.contains("password authentication failed") || e.contains("access denied") || e.contains("not authorized")) {
            return "The service refused the credentials. Check the user and what the password reference resolves to.";
        }
        return null;
    }

    // -- health ----------------------------------------------------------------------------------------------------

    /** For the Health page: counts and what needs attention. */
    public Map<String, Object> healthSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> rows = list();
        long failed = rows.stream().filter(r -> "FAILED".equals(r.get("state"))).count();
        List<String> badFiles = new ArrayList<>(new TreeSet<>(fileProblems.keySet()));
        m.put("count", rows.size());
        m.put("failed", failed);
        m.put("badFiles", badFiles);
        m.put("problems", new LinkedHashMap<>(new java.util.TreeMap<>(fileProblems)));
        m.put("watch", watchState());
        m.put("directory", files.dir().toString());
        m.put("deprecated", new ArrayList<>(applicationDefined));
        return m;
    }

    public Map<String, String> fileProblems() {
        return Map.copyOf(fileProblems);
    }
}
