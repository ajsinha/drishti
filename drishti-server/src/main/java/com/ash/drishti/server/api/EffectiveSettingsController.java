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
package com.ash.drishti.server.api;

import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The settings in effect on this server and where each came from (docs/admin/CONFIGURATION.md, 2.9.7 C3): Help → Configuration shows
 * them beside the documented defaults, for administrators only. Every key of the {@code drishti}, {@code server}, {@code spring},
 * {@code management}, {@code logging} and {@code springdoc} trees any configuration file, argument or pack contributes, with its value
 * and its origin: {@code file} (which one), {@code env} (the variable), {@code argument}, {@code system-property}, {@code pack} or
 * {@code default} (the bundled application.yaml's own value). A list's items are joined into one value. Secrets are never shown: a key
 * with a word secret, password, token, key or credential in it, or one of the known secret settings, answers {@code ••••} (empty when
 * unset), and a URL's user information is masked too.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
public class EffectiveSettingsController {

    static final String MASK = "••••";
    static final List<String> TREES = List.of("drishti.", "server.", "spring.", "management.", "logging.", "springdoc.");
    static final Set<String> KNOWN_SECRETS = Set.of("drishti.security.secret", "drishti.security.metrics-token", "spring.mail.password",
            "spring.datasource.password", "drishti.identity.database-password", "drishti.identity.seed-password", "drishti.http.proxy.password");
    private static final Set<String> SECRET_WORDS = Set.of("secret", "secrets", "password", "passwd", "passphrase", "token", "key", "credential",
            "credentials", "apikey");
    private static final Pattern WORDS = Pattern.compile("[.\\-_\\[\\]]+");
    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)]$");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)[:}]");
    private static final Pattern USERINFO = Pattern.compile("(//[^/@:\\s]+:)[^/@\\s]+@");

    private final ConfigurableEnvironment env;
    private final Entitlements entitlements;

    public EffectiveSettingsController(ConfigurableEnvironment env, Entitlements entitlements) {
        this.env = env;
        this.entitlements = entitlements;
    }

    /** {@code {settings: [{key, value, origin, source, secret}]}}, sorted by key. */
    @GetMapping
    public Map<String, Object> effective(@RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        entitlements.requireAdmin(p);
        return snapshot(env, System::getenv);
    }

    static Map<String, Object> snapshot(ConfigurableEnvironment env, Function<String, String> getenv) {
        Map<String, TreeMap<Integer, String>> lists = new TreeMap<>();
        Set<String> names = new java.util.TreeSet<>();
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (!(ps instanceof EnumerablePropertySource<?> e) || isEnvironment(ps.getName())) {
                continue;                                    // the process environment and system properties: every variable, not settings
            }
            for (String name : e.getPropertyNames()) {
                if (TREES.stream().noneMatch(name::startsWith)) {
                    continue;
                }
                names.add(name);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Object>> byList = new TreeMap<>();
        for (String name : names) {
            Map<String, Object> row = row(env, name, getenv);
            Matcher m = INDEX.matcher(name);
            if (m.find() && name.indexOf('[') == m.start()) {
                String base = name.substring(0, m.start());
                lists.computeIfAbsent(base, k -> new TreeMap<>()).put(Integer.parseInt(m.group(1)), String.valueOf(row.get("value")));
                byList.putIfAbsent(base, row);
                continue;
            }
            rows.add(row);
        }
        for (Map.Entry<String, TreeMap<Integer, String>> l : lists.entrySet()) {
            Map<String, Object> first = byList.get(l.getKey());
            Map<String, Object> row = new LinkedHashMap<>(first);
            row.put("key", l.getKey());
            boolean secret = isSecret(l.getKey());
            row.put("secret", secret);
            row.put("value", secret ? MASK : String.join(", ", l.getValue().values()));
            rows.add(row);
        }
        rows.sort((a, b) -> String.valueOf(a.get("key")).compareTo(String.valueOf(b.get("key"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("settings", rows);
        out.put("profiles", List.of(env.getActiveProfiles()));
        return out;
    }

    private static Map<String, Object> row(ConfigurableEnvironment env, String key, Function<String, String> getenv) {
        String value;
        try {
            value = env.getProperty(key);
        } catch (RuntimeException unresolvable) {
            value = null;                                    // a placeholder nothing resolves: shown as not set
        }
        boolean secret = isSecret(key);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("value", secret ? (value == null || value.isEmpty() ? "" : MASK) : maskUrl(value));
        row.put("secret", secret);
        String[] origin = origin(env, key, getenv);
        row.put("origin", origin[0]);
        row.put("source", origin[1]);
        return row;
    }

    /** {kind, detail}: the first property source (the one that wins) that holds the key. */
    static String[] origin(ConfigurableEnvironment env, String key, Function<String, String> getenv) {
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (!ps.containsProperty(key)) {
                continue;
            }
            String name = ps.getName();
            if (name.startsWith("systemEnvironment")) {
                return new String[] {"env", key.toUpperCase(Locale.ROOT).replace('.', '_').replace("-", "")};
            }
            if (name.startsWith("systemProperties")) {
                return new String[] {"system-property", key};
            }
            if (name.contains("commandLineArgs")) {
                return new String[] {"argument", "--" + key};
            }
            Object raw = ps.getProperty(key);
            if (raw != null) {
                Matcher m = PLACEHOLDER.matcher(String.valueOf(raw));
                while (m.find()) {
                    String var = m.group(1);
                    if (getenv.apply(var) != null) {
                        return new String[] {"env", var};
                    }
                }
            }
            String file = fileOf(name);
            if (file == null) {
                return new String[] {name.toLowerCase(Locale.ROOT).contains("pack") ? "pack" : "other", name};
            }
            return new String[] {file.equals("application.yaml") && name.contains("classpath:") ? "default" : "file", file};
        }
        return new String[] {"default", "the code"};
    }

    private static boolean isEnvironment(String name) {
        return name.startsWith("systemEnvironment") || name.startsWith("systemProperties");
    }

    /** {@code Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'} -> application.yaml. */
    private static String fileOf(String sourceName) {
        Matcher m = Pattern.compile("\\[([^\\]]+\\.(?:ya?ml|properties))]").matcher(sourceName);
        if (m.find()) {
            String f = m.group(1);
            return f.substring(f.lastIndexOf('/') + 1);
        }
        return null;
    }

    static boolean isSecret(String key) {
        if (KNOWN_SECRETS.contains(key)) {
            return true;
        }
        for (String w : WORDS.split(key.toLowerCase(Locale.ROOT))) {
            if (SECRET_WORDS.contains(w)) {
                return true;
            }
        }
        return false;
    }

    static String maskUrl(String value) {
        return value == null ? null : USERINFO.matcher(value).replaceAll("$1" + MASK + "@");
    }
}
