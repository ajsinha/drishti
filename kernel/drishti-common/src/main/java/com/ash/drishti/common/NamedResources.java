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
package com.ash.drishti.common;

import com.ash.drishti.api.DrishtiTools;
import com.ash.tools.settings.Placeholders;
import com.ash.tools.settings.SettingsFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Named lakes, databases and brokers (docs/admin/CONFIGURATION.md, "Named lakes, databases and brokers"). A system has as many of each as
 * it needs, every one a section of the settings files named by kind and name, and everything that uses one refers to it by that name;
 * there is no global lake, database or broker:
 * <pre>
 * [lake.trading]      root=/srv/lakes/trading          storage.s3.region=...      (a lake file config/lakes/trading.yaml holds its tables)
 * [db.reporting]      url=jdbc:postgresql://...         user=...    password=${REPORTING_DB_PASSWORD}
 * [broker.kafka-eu]   type=kafka   bootstrap-servers=...   security.protocol=SASL_SSL   sasl.mechanism=SCRAM-SHA-512   tls.ca-file=/...
 * [connector.trade-murex-live]   broker=kafka-eu   topics=murex.trades       (any setting of one connector, key by key)
 * [pack.market-risk]  lake=risk                         (the pack's connectors that read the shared demo lake read this lake instead)
 * </pre>
 * A connector names its broker with the setting {@code broker:} and its database with {@code db:}; the section's keys are the plugin's own
 * settings ({@code bootstrap-servers}, {@code broker-url}, {@code uri}, {@code url}, {@code user}, {@code password}, {@code tls.*},
 * {@code sasl.*}, {@code client.*} ...), merged under the connector's own, so a connector says only what is its own (topics, queues,
 * tables). The documented default names: lake {@value #DEFAULT_LAKE}, database {@value #DEFAULT_DB}, broker {@value #DEFAULT_BROKER} (a
 * Kafka at {@code localhost:9092} when no {@code [broker.default]} section says otherwise).
 *
 * <p>A section's keys are read through the server's view ({@link #useView}: its system properties, which hold the settings files'
 * entries, its environment and its configuration) or, with no server, the process's; a key's environment form
 * ({@code broker.kafka-eu.sasl.password} is {@code BROKER_KAFKA_EU_SASL_PASSWORD}) is read for every key the files name and every
 * documented key ({@link #KNOWN}). Values are resolved by the one placeholder resolver ({@link Placeholders}). Every relative file or
 * folder of the merged settings is taken under the install folder ({@code drishti.home}), never the working folder.
 */
public final class NamedResources {

    /** The kind of a lake section, {@code [lake.<name>]}. */
    public static final String LAKE = "lake";
    /** The kind of a database section, {@code [db.<name>]}. */
    public static final String DB = "db";
    /** The kind of a broker section, {@code [broker.<name>]}. */
    public static final String BROKER = "broker";
    /** One connector's own settings, {@code [connector.<name>]}: they win over its file and its template, key by key. */
    public static final String CONNECTOR = "connector";
    /** One pack's bindings, {@code [pack.<name>]}. */
    public static final String PACK = "pack";
    /** The lake the shipped packs read when nothing names another (config/lakes/demo.yaml). */
    public static final String DEFAULT_LAKE = "demo";
    /** The database of users, roles, workspaces and the audit trail ({@code drishti.identity.database-url}). */
    public static final String DEFAULT_DB = "identity";
    /** The broker the shipped streaming templates name. */
    public static final String DEFAULT_BROKER = "default";
    /** The connector settings that name a resource, with the kind each names. */
    public static final Map<String, String> REFERENCES = Map.of("broker", BROKER, "db", DB);
    /** A resource's name: lower-case letters, digits, '-' and '_' (as a lake's). */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]*");
    /** The broker plugins, whose {@code type} must match the broker's. */
    private static final Set<String> BROKER_TYPES = Set.of("kafka", "activemq", "rabbitmq");
    /** The default broker when no section names its keys: a Kafka on this machine (the plugin's own default). */
    private static final Map<String, String> DEFAULT_BROKER_KEYS = Map.of("type", "kafka", "bootstrap-servers", "localhost:9092");
    /** A setting that names a file or a folder: anchored under the install folder when relative. */
    private static final Pattern PATH_KEY = Pattern.compile(
            "(?:^|[.-])(?:root|dir|dirs|folder|file|keytab|krb5-conf|truststore|keystore|ticket-cache)$|^url$|[.-]url$");

    /**
     * The documented keys of a section, read from the environment form too (the files' own keys are always read): the brokers' and the
     * databases' connection, the shared TLS module's, Kafka's SASL and Confluent keys, and a lake's root and format.
     */
    public static final List<String> KNOWN = List.of("type", "bootstrap-servers", "broker-url", "uri", "url", "user", "username", "password",
            "flavour", "security.protocol", "sasl.mechanism", "sasl.username", "sasl.password", "sasl.password-file", "api-key", "api-secret",
            "sasl.oauth.token-endpoint", "sasl.oauth.client-id", "sasl.oauth.client-secret", "sasl.oauth.scope", "sasl.kerberos.principal",
            "sasl.kerberos.keytab", "sasl.kerberos.service-name", "sasl.kerberos.use-ticket-cache", "sasl.kerberos.krb5-conf", "auth-mechanism",
            "client-id", "tls.enabled", "tls.ca-file", "tls.trust-jvm-default", "tls.truststore", "tls.truststore-type", "tls.truststore-password",
            "tls.truststore-password-file", "tls.cert-file", "tls.key-file", "tls.key-password", "tls.key-password-file", "tls.keystore",
            "tls.keystore-type", "tls.keystore-password", "tls.keystore-password-file", "tls.key-alias", "tls.verify-hostname", "tls.protocols",
            "tls.cipher-suites", "root", "format", "lake");

    private static volatile Function<String, String> lookup;
    private static volatile Supplier<Collection<String>> names = NamedResources::processNames;

    private NamedResources() {
    }

    /**
     * Installs the server's view: {@code lookup} reads one key ({@code env.getProperty}), {@code keys} lists every key its sources name.
     * Null for either: the process's (system properties, which hold the settings files' entries, then the environment).
     */
    public static void useView(Function<String, String> lookup, Supplier<Collection<String>> keys) {
        NamedResources.lookup = lookup;
        NamedResources.names = keys == null ? NamedResources::processNames : keys;
    }

    /** The process's keys: its system properties (the settings files' entries among them) and its environment's names (a loader started by
     * the server is handed {@code lake.<name>.*} there, {@link #all}). */
    private static Collection<String> processNames() {
        Set<String> out = new LinkedHashSet<>(System.getProperties().stringPropertyNames());
        out.addAll(System.getenv().keySet());
        return out;
    }

    /** One key through the view, resolved; null when nothing names it. */
    public static String get(String key) {
        String v = Placeholders.lookup(key, lookup);
        return v == null ? null : Placeholders.resolve(v, lookup);
    }

    /** Whether {@code name} is a resource's name. */
    public static boolean validName(String name) {
        return name != null && NAME.matcher(name).matches();
    }

    /**
     * The keys of {@code [<kind>.<name>]}, without the prefix, values resolved, in key order: every key the sources name under
     * {@code <kind>.<name>.}, and every {@link #KNOWN} key (and each of {@code also}) its environment form gives. Empty when none.
     */
    public static Map<String, String> section(String kind, String name, Collection<String> also) {
        String prefix = kind + "." + name + ".";
        Set<String> keys = new LinkedHashSet<>();
        for (String k : List.copyOf(names.get())) {
            if (k.startsWith(prefix) && k.length() > prefix.length()) {
                keys.add(k.substring(prefix.length()));
            }
        }
        keys.addAll(KNOWN);
        if (also != null) {
            keys.addAll(also);
        }
        Map<String, String> out = new TreeMap<>();
        for (String k : keys) {
            String v = get(prefix + k);
            if (v != null) {
                out.put(k, v);
            }
        }
        return out;
    }

    /**
     * Every key the sources name under {@code <kind>.} ({@code lake.}), full name to resolved value, in key order: what a process started
     * from this one is handed so it reads the same sections.
     */
    public static Map<String, String> all(String kind) {
        Map<String, String> out = new TreeMap<>();
        for (String k : List.copyOf(names.get())) {
            if (k.startsWith(kind + ".")) {
                String v = get(k);
                if (v != null) {
                    out.put(k, v);
                }
            }
        }
        return out;
    }

    /** As {@link #section(String, String, Collection)} with the documented keys only. */
    public static Map<String, String> section(String kind, String name) {
        return section(kind, name, List.of());
    }

    /**
     * A connector's settings as its plugin is given them (the one place, for every connector, whichever file or template it came from):
     * <ol>
     *   <li>{@code [connector.<name>]}: each key wins over the connector's own;</li>
     *   <li>{@code broker: <name>} / {@code db: <name>}: that section's keys under the connector's (a blank own value does not hide one);
     *       its {@code type} must be the plugin's for a broker plugin; a name no section gives is an error naming the section to write
     *       (the {@value #DEFAULT_BROKER} broker needs none: a Kafka at localhost:9092);</li>
     *   <li>every relative file or folder taken under the install folder ({@code drishti.home}).</li>
     * </ol>
     *
     * @throws IllegalArgumentException a reference to a resource nothing configures, a bad name, or a broker of another type
     */
    public static Map<String, String> connector(String connector, String plugin, Map<String, String> settings) {
        Map<String, String> own = new LinkedHashMap<>(settings == null ? Map.of() : settings);
        if (connector != null && !connector.isBlank()) {
            own.putAll(section(CONNECTOR, connector, own.keySet()));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> ref : new TreeMap<>(REFERENCES).entrySet()) {
            String named = own.get(ref.getKey());
            if (named == null || named.isBlank()) {
                continue;
            }
            String name = named.strip();
            String kind = ref.getValue();
            if (!validName(name)) {
                throw new IllegalArgumentException("connector " + connector + ": " + ref.getKey() + " '" + name
                        + "' is not a name (lower-case letters, digits, '-' and '_')");
            }
            Map<String, String> base = new LinkedHashMap<>();
            if (kind.equals(BROKER) && name.equals(DEFAULT_BROKER)) {
                base.putAll(DEFAULT_BROKER_KEYS);
            }
            base.putAll(section(kind, name, own.keySet()));
            if (base.isEmpty()) {
                throw new IllegalArgumentException("connector " + connector + " names " + kind + " " + name + ", which nothing configures: write a ["
                        + kind + "." + name + "] section in the settings file (" + DrishtiTools.SETTINGS_FILE + "), or set " + kind + "." + name + ".* keys");
            }
            String type = base.remove("type");
            if (kind.equals(BROKER) && type != null && plugin != null && BROKER_TYPES.contains(plugin) && !type.equalsIgnoreCase(plugin)) {
                throw new IllegalArgumentException("connector " + connector + " (plugin " + plugin + ") names broker " + name + ", of type " + type
                        + ": name a broker of type " + plugin);
            }
            out.putAll(base);
        }
        own.forEach((k, v) -> {
            if (v != null && (!v.isBlank() || !out.containsKey(k))) {
                out.put(k, v);
            }
        });
        return anchored(out);
    }

    /**
     * The lake a pack's connector reads: {@code pack.<pack>.lake} for a connector of that pack whose template names the shared
     * {@value #DEFAULT_LAKE} lake, so one pack's data moves without moving another's; {@code lake} as given otherwise.
     */
    public static String packLake(String pack, String lake) {
        if (pack == null || lake == null || !DEFAULT_LAKE.equals(lake.strip())) {
            return lake;
        }
        String bound = get(PACK + "." + pack + ".lake");
        return bound == null || bound.isBlank() ? lake : bound.strip();
    }

    /** The settings with every relative file or folder taken under the install folder; unchanged when no install folder is named. */
    public static Map<String, String> anchored(Map<String, String> settings) {
        Path home;
        try {
            home = com.ash.drishti.api.Roots.home();
        } catch (IllegalStateException e) {
            return settings;                                   // a unit test with no install folder: nothing to anchor against
        }
        Map<String, String> out = new LinkedHashMap<>(settings);
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> e : settings.entrySet()) {
            String k = e.getKey();
            if (e.getValue() != null && PATH_KEY.matcher(k).find() && !k.startsWith("client.") && !k.contains(".routes.")) {
                out.put(k, com.ash.drishti.api.Roots.anchorValue(k, e.getValue(), home, problems));
            }
        }
        return out;
    }

    /** Settings as they may be shown: every secret masked. */
    public static Map<String, String> shown(Map<String, String> settings) {
        Map<String, String> out = new LinkedHashMap<>();
        settings.forEach((k, v) -> out.put(k, SettingsFile.isSecret(k) || ConnectorSecrets.secretKey(k) ? SettingsFile.shown("password", v) : v));
        return out;
    }
}
