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
package com.ash.drishti.server.deploy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.ConnectionProbe;
import com.ash.drishti.packs.Pack;
import com.ash.drishti.packs.PackEnvironmentPostProcessor;
import com.ash.drishti.packs.PackRegistry;
import com.ash.drishti.packs.PackSettings;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * The data source of a pack, in three layers. Highest wins: the <b>site</b> (environment variables and the server's own
 * configuration, such as {@code DRISHTI_SOURCES_CONNECTORS_RISK_STORE_SETTINGS_ROOT}), then the administrator's <b>override</b>
 * file ({@code data/packs/settings/<pack>.yaml}, see {@link PackSettings}), then the <b>pack</b>'s own settings. This class
 * shows the layers per setting, validates an edit, and tries settings against the real source ({@link ConnectionProbe})
 * without changing anything. Applying an edit (write, check, restart in place with automatic undo) is the controller's step.
 */
public final class DataSourceService {

    private final PackRegistry packs;
    private final PackSettings settings;
    private final ConfigurableEnvironment env;
    private final ConnectionProbe probe;
    private final DeployProperties props;

    public DataSourceService(PackRegistry packs, PackSettings settings, ConfigurableEnvironment env, ConnectionProbe probe, DeployProperties props) {
        this.packs = packs;
        this.settings = settings;
        this.env = env;
        this.probe = probe;
        this.props = props;
    }

    public PackSettings settings() {
        return settings;
    }

    private Pack pack(String name) {
        return packs.packs().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow(
                () -> new DrishtiException(ErrorCode.ENTITY_NOT_FOUND, "'" + name + "' is not a loaded pack (load it first: Admin → Packs → Load)"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<String> kinds(Object o) {
        return o instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    /** Every source but the pack's own, and but Spring's view that merges all of them (it would claim the pack's values as the site's). */
    private static boolean siteSource(PropertySource<?> ps) {
        return !PackEnvironmentPostProcessor.SOURCE.equals(ps.getName()) && !"configurationProperties".equals(ps.getName());
    }

    /** What a site source (not the pack's own) says for a connector setting, or null. */
    private String site(String connector, String key) {
        String name = "drishti.sources.connectors." + connector + ".settings." + key;
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (siteSource(ps) && ps.containsProperty(name)) {
                Object v = ps.getProperty(name);
                return v == null ? null : String.valueOf(v);
            }
        }
        return null;
    }

    private String siteEnabled(String connector) {
        String name = "drishti.sources.connectors." + connector + ".enabled";
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (siteSource(ps) && ps.containsProperty(name)) {
                return String.valueOf(ps.getProperty(name));
            }
        }
        return null;
    }

    /** The resolved text, or null when a ${variable} in it is not set. */
    private String resolve(String raw) {
        try {
            return env.resolveRequiredPlaceholders(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Every connector of the pack with each setting's pack value, override, site value and the one that wins. */
    public Map<String, Object> get(String name) {
        Pack pack = pack(name);
        Map<String, Object> declared = map(pack.manifest().get("connectors"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pack", name);
        out.put("file", settings.file(name).toString());
        out.put("overridden", settings.text(name) != null);
        out.put("plugins", probe.plugins());
        List<Map<String, Object>> conns = new ArrayList<>();
        for (Map.Entry<String, Object> e : declared.entrySet()) {
            String cn = e.getKey();
            Map<String, Object> c = map(e.getValue());
            Map<String, Object> ov = settings.connector(name, cn);
            Map<String, String> packFlat = PackSettings.flat(map(c.get("settings")));
            Map<String, String> ovFlat = PackSettings.flat(map(ov.get("settings")));
            Set<String> keys = new LinkedHashSet<>(packFlat.keySet());
            keys.addAll(ovFlat.keySet());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", cn);
            row.put("plugin", c.get("plugin"));
            row.put("kinds", kinds(c.get("kinds")));
            Map<String, Object> en = new LinkedHashMap<>();
            en.put("pack", c.get("enabled") == null ? null : String.valueOf(c.get("enabled")));
            en.put("override", ov.get("enabled") == null ? null : String.valueOf(ov.get("enabled")));
            en.put("site", siteEnabled(cn));
            String enRaw = en.get("site") != null ? (String) en.get("site") : en.get("override") != null ? (String) en.get("override")
                    : en.get("pack") != null ? (String) en.get("pack") : "true";
            String enResolved = resolve(enRaw);
            en.put("effective", enResolved == null ? enRaw : enResolved);
            en.put("on", enResolved == null || Boolean.parseBoolean(enResolved));
            en.put("source", en.get("site") != null ? "site" : en.get("override") != null ? "override" : "pack");
            row.put("enabled", en);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (String k : keys) {
                Map<String, Object> s = new LinkedHashMap<>();
                String pv = packFlat.get(k);
                String ovv = ovFlat.get(k);
                String sv = site(cn, k);
                String raw = sv != null ? sv : ovv != null ? ovv : pv;
                boolean secret = PackSettings.secret(k);
                String resolved = resolve(raw);
                s.put("key", k);
                s.put("pack", pv);
                s.put("override", ovv);
                s.put("site", secret && sv != null ? "(set by the site)" : sv);
                s.put("effective", secret && sv != null ? "(set by the site)" : raw);
                s.put("resolved", secret ? null : resolved);
                s.put("resolvable", resolved != null);
                s.put("secret", secret);
                s.put("source", sv != null ? "site" : ovv != null ? "override" : "pack");
                s.put("overridden", ovv != null);
                rows.add(s);
            }
            row.put("settings", rows);
            conns.add(row);
        }
        out.put("connectors", conns);
        return out;
    }

    /**
     * An edit as the document to store: only what differs from the pack's own values stays, so "pack default" is what an
     * untouched setting shows. Validates and throws {@code 400} with every problem.
     */
    public Map<String, Object> normalise(String name, Map<String, Object> body) {
        Pack pack = pack(name);
        Map<String, Object> declared = map(pack.manifest().get("connectors"));
        Map<String, Object> asked = map(body.get("connectors"));
        List<String> problems = PackSettings.validate(Map.of("connectors", asked), declared);
        if (!problems.isEmpty()) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the data source is not valid: " + String.join("; ", problems));
        }
        Map<String, Object> conns = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : asked.entrySet()) {
            Map<String, Object> c = map(declared.get(e.getKey()));
            Map<String, Object> want = map(e.getValue());
            Map<String, String> packFlat = PackSettings.flat(map(c.get("settings")));
            Map<String, Object> keep = new LinkedHashMap<>();
            if (want.get("enabled") instanceof Boolean b && !String.valueOf(b).equals(String.valueOf(c.get("enabled")))) {
                keep.put("enabled", b);
            }
            Map<String, Object> st = new LinkedHashMap<>();
            map(want.get("settings")).forEach((k, v) -> {
                String s = v == null ? null : String.valueOf(v);
                if (s != null && !s.equals(packFlat.get(k))) {
                    st.put(k, s);
                }
            });
            if (!st.isEmpty()) {
                keep.put("settings", st);
            }
            if (!keep.isEmpty()) {
                conns.put(e.getKey(), keep);
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        if (!conns.isEmpty()) {
            doc.put("connectors", conns);
        }
        return doc;
    }

    /** The document with one connector's override removed ({@code connector} null: all of it). */
    public Map<String, Object> without(String name, String connector) {
        pack(name);
        if (connector == null || connector.isBlank()) {
            return Map.of();
        }
        Map<String, Object> doc = new LinkedHashMap<>(settings.read(name));
        Map<String, Object> conns = new LinkedHashMap<>(map(doc.get("connectors")));
        conns.remove(connector);
        if (conns.isEmpty()) {
            doc.remove("connectors");
        } else {
            doc.put("connectors", conns);
        }
        return doc;
    }

    /** The names of the settings that differ between two documents (for the audit row; values are not recorded). */
    public List<String> changed(String name, Map<String, Object> after) {
        Map<String, Object> before = settings.read(name);
        Set<String> a = flatNames(before);
        Set<String> b = flatNames(after);
        Set<String> out = new LinkedHashSet<>();
        for (String x : a) {
            if (!b.contains(x) || !valueOf(before, x).equals(valueOf(after, x))) {
                out.add(x);
            }
        }
        for (String x : b) {
            if (!a.contains(x)) {
                out.add(x);
            }
        }
        return List.copyOf(out);
    }

    private static Set<String> flatNames(Map<String, Object> doc) {
        Set<String> out = new LinkedHashSet<>();
        map(doc.get("connectors")).forEach((cn, c) -> {
            if (map(c).get("enabled") != null) {
                out.add(cn + ".enabled");
            }
            map(map(c).get("settings")).keySet().forEach(k -> out.add(cn + "." + k));
        });
        return out;
    }

    private static String valueOf(Map<String, Object> doc, String flat) {
        int dot = flat.indexOf('.');
        Map<String, Object> c = map(map(doc.get("connectors")).get(flat.substring(0, dot)));
        String k = flat.substring(dot + 1);
        return String.valueOf("enabled".equals(k) ? c.get("enabled") : map(c.get("settings")).get(k));
    }

    /** Tries the connectors with the stored override (body null) or with a candidate document, changing nothing. */
    public Map<String, Object> test(String name, Map<String, Object> candidate, String onlyConnector) {
        Pack pack = pack(name);
        Map<String, Object> declared = map(pack.manifest().get("connectors"));
        Map<String, Object> doc = candidate == null ? settings.read(name) : normalise(name, candidate);
        Map<String, Object> conns = map(doc.get("connectors"));
        if (onlyConnector != null && !onlyConnector.isBlank() && !declared.containsKey(onlyConnector)) {
            throw new DrishtiException(ErrorCode.BAD_REQUEST, "the pack has no connector '" + onlyConnector + "'");
        }
        List<Future<Map<String, Object>>> work = new ArrayList<>();
        try (var exec = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Map.Entry<String, Object> e : declared.entrySet()) {
                String cn = e.getKey();
                if (onlyConnector != null && !onlyConnector.isBlank() && !onlyConnector.equals(cn)) {
                    continue;
                }
                work.add(exec.submit(() -> testOne(name, cn, map(e.getValue()), map(conns.get(cn)))));
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (Future<Map<String, Object>> f : work) {
                try {
                    results.add(f.get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new DrishtiException(ErrorCode.BAD_REQUEST, "interrupted");
                } catch (java.util.concurrent.ExecutionException ex) {
                    throw new DrishtiException(ErrorCode.SOURCE_FAILED, String.valueOf(ex.getCause().getMessage()));
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pack", name);
            out.put("tested", candidate == null ? "the settings in force (pack + override + site)" : "the edited settings, not yet saved");
            out.put("ok", results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("ok")) || Boolean.TRUE.equals(r.get("skipped"))));
            out.put("connectors", results);
            return out;
        }
    }

    private Map<String, Object> testOne(String pack, String cn, Map<String, Object> c, Map<String, Object> ov) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("connector", cn);
        r.put("plugin", c.get("plugin"));
        String enRaw = siteEnabled(cn) != null ? siteEnabled(cn) : ov.get("enabled") != null ? String.valueOf(ov.get("enabled"))
                : c.get("enabled") != null ? String.valueOf(c.get("enabled")) : "true";
        String en = resolve(enRaw);
        if (en != null && !Boolean.parseBoolean(en)) {
            r.put("ok", false);
            r.put("skipped", true);
            r.put("error", "switched off (enabled: " + en + "); nothing was tried");
            return r;
        }
        Map<String, String> eff = new LinkedHashMap<>(PackSettings.flat(map(c.get("settings"))));
        eff.putAll(PackSettings.flat(map(ov.get("settings"))));
        List<String> secrets = new ArrayList<>();
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : new ArrayList<>(eff.entrySet())) {
            String sv = site(cn, e.getKey());
            String raw = sv != null ? sv : e.getValue();
            String v = resolve(raw);
            if (v == null) {
                r.put("ok", false);
                r.put("error", "setting '" + e.getKey() + "' refers to an environment variable that is not set: " + raw);
                return r;
            }
            resolved.put(e.getKey(), v);
            if (PackSettings.secret(e.getKey()) && !v.isBlank()) {
                secrets.add(v);
            }
        }
        ConnectionProbe.Result p = probe.probe(String.valueOf(c.get("plugin")), kinds(c.get("kinds")), resolved, props.probeDates(), props.probeTimeoutSeconds() * 1000L);
        r.put("ok", p.ok());
        r.put("health", p.health());
        r.put("ms", p.ms());
        String err = p.error();
        for (String s : secrets) {
            err = err == null ? null : err.replace(s, "***");
        }
        r.put("error", err);
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (ConnectionProbe.KindRows k : p.kinds()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", k.kind());
            m.put("exact", k.exact());
            m.put("note", k.note());
            m.put("dates", k.dates().stream().map(d -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("date", d.date() == null ? null : d.date().toString());
                x.put("rows", d.rows());
                return x;
            }).toList());
            kinds.add(m);
        }
        r.put("kinds", kinds);
        return r;
    }
}
