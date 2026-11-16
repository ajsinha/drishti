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
package com.ash.drishti.server.loads;

import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.packs.Pack;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a pack expects of its loads and who is told, as it is in force: the pack's own {@code loads:} section in {@code pack.yaml},
 * overlaid by the administrator's override file ({@code <loads dir>/config/<pack>.yaml}, written from the Data loads page; the pack's
 * files are never touched, so a redeploy keeps it).
 *
 * <pre>
 * loads:
 *   notify:
 *     roles: [risk-ops]          # everyone with one of these roles (who may open the kind) is told
 *     users: [ada]
 *     email: true                # also queue an email (needs the collaboration email channel)
 *   smoke: 3                     # open this many sample entities after each ready load (0 = off)
 *   expect:
 *     trade:   { by: "19:00", zone: America/New_York, calendar: USNY }   # zone and calendar are optional
 * </pre>
 *
 * @param smoke sample entities opened after a ready load (0 = none)
 * @param expect per kind, when its load is due each business day
 * @param origin {@code pack}, {@code override} (the administrator's file exists) or {@code default}
 */
public record LoadsConfig(List<String> notifyRoles, List<String> notifyUsers, boolean email, int smoke, Map<String, Expect> expect, String origin) {

    /**
     * When a kind's load is due on a business day.
     *
     * @param by local time of day, {@code HH:mm}
     * @param zone zone of {@code by}; empty = the server's business-date zone
     * @param calendar business-day calendar; empty = the server's calendar
     */
    public record Expect(String by, String zone, String calendar) {
        public LocalTime time() {
            return LocalTime.parse(by);
        }
    }

    public LoadsConfig {
        notifyRoles = List.copyOf(notifyRoles);
        notifyUsers = List.copyOf(notifyUsers);
        expect = Map.copyOf(expect);
    }

    /** The configuration as a plain map, for the API and the override file. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> n = new LinkedHashMap<>();
        n.put("roles", notifyRoles);
        n.put("users", notifyUsers);
        n.put("email", email);
        m.put("notify", n);
        m.put("smoke", smoke);
        Map<String, Object> e = new LinkedHashMap<>();
        new java.util.TreeMap<>(expect).forEach((k, v) -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("by", v.by());
            if (!v.zone().isEmpty()) {
                x.put("zone", v.zone());
            }
            if (!v.calendar().isEmpty()) {
                x.put("calendar", v.calendar());
            }
            e.put(k, x);
        });
        m.put("expect", e);
        return m;
    }

    /** Reads and writes the override files and builds the configuration in force. */
    public static final class Overrides {

        private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
        private final Path dir;
        private final List<String> defaultRoles;
        private final int smokeMax;
        private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory().disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES).enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS));

        /** The shared overrides ({@code loads/config/<pack>.yaml} in the content store, DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 11), or null for the files. */
        private final com.ash.drishti.identity.content.ContentStore store;
        static final String PREFIX = "loads/config/";
        static final String MARKER = "system/imports/loads-config.json";
        private static final String HEADER = "# Data loads override, written from Admin > Packs > Data loads. The pack's own files are unchanged;\n"
                + "# delete this file (or press Reset) to use the pack's own loads: section again.\n";

        public Overrides(Path dir, List<String> defaultRoles, int smokeMax) {
            this(dir, defaultRoles, smokeMax, null);
        }

        /**
         * With a store, an override saved through Admin is a document every node reads ({@code drishti.loads.config-store: content});
         * the folder's files are imported once (marker {@code system/imports/loads-config.json}) and left in place.
         */
        public Overrides(Path dir, List<String> defaultRoles, int smokeMax, com.ash.drishti.identity.content.ContentStore store) {
            this.dir = dir.toAbsolutePath().normalize();
            this.defaultRoles = defaultRoles;
            this.smokeMax = smokeMax;
            this.store = store;
            if (store != null) {
                importFiles();
            }
        }

        private void importFiles() {
            try {
                store.put(MARKER, ("{\"at\":\"" + java.time.Instant.now() + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
            } catch (com.ash.drishti.common.DrishtiException e) {
                return;                                                   // imported before (or by another node now)
            }
            Path folder = dir.resolve("config");
            if (!Files.isDirectory(folder)) {
                return;
            }
            try (var files = Files.list(folder)) {
                for (Path f : files.filter(x -> x.getFileName().toString().endsWith(".yaml")).toList()) {
                    String pack = f.getFileName().toString().replaceAll("\\.yaml$", "");
                    if (NAME.matcher(pack).matches() && store.get(PREFIX + pack + ".yaml").isEmpty()) {
                        store.put(PREFIX + pack + ".yaml", Files.readAllBytes(f), null);
                        org.slf4j.LoggerFactory.getLogger(Overrides.class).info("data loads override {} imported into the content store ({} is kept)", pack, f);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot import " + folder, e);
            }
        }

        private String key(String pack) {
            file(pack);                                                   // checks the name
            return PREFIX + pack + ".yaml";
        }

        public Path file(String pack) {
            if (!NAME.matcher(pack).matches()) {
                throw new IllegalArgumentException("not a pack name: " + pack);
            }
            return dir.resolve("config").resolve(pack + ".yaml");
        }

        public boolean overridden(String pack) {
            return store != null ? store.get(key(pack)).isPresent() : Files.isRegularFile(file(pack));
        }

        private Map<String, Object> overrideDoc(String pack) {
            if (store != null) {
                var item = store.get(key(pack));
                if (item.isEmpty()) {
                    return Map.of();
                }
                try {
                    Map<String, Object> m = yaml.readValue(item.get().bytes(), new TypeReference<Map<String, Object>>() {});
                    return m == null ? Map.of() : m;
                } catch (IOException e) {
                    throw new UncheckedIOException("cannot read " + key(pack) + " in the content store", e);
                }
            }
            Path f = file(pack);
            if (!Files.isRegularFile(f)) {
                return Map.of();
            }
            try {
                Map<String, Object> m = yaml.readValue(f.toFile(), new TypeReference<Map<String, Object>>() {});
                return m == null ? Map.of() : m;
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + f, e);
            }
        }

        /** The configuration in force for the pack: its own section, then the administrator's override on top. */
        public LoadsConfig effective(Pack pack) {
            Object own = pack.manifest().get("loads");
            Map<String, Object> over = overrideDoc(pack.name());
            Map<String, Object> merged = new LinkedHashMap<>();
            if (own instanceof Map<?, ?> m) {
                m.forEach((k, v) -> merged.put(String.valueOf(k), v));
            }
            over.forEach(merged::put);            // an override replaces a whole section (notify, smoke, expect)
            return parse(merged, over.isEmpty() ? own == null ? "default" : "pack" : "override", true);
        }

        /** Validates a document (an override being saved); throws {@code 400} with every problem. */
        public LoadsConfig validate(Map<String, Object> doc) {
            return parse(doc, "override", false);
        }

        @SuppressWarnings("unchecked")
        private LoadsConfig parse(Map<String, Object> doc, String origin, boolean lenient) {
            List<String> problems = new ArrayList<>();
            List<String> roles = new ArrayList<>();
            List<String> users = new ArrayList<>();
            boolean email = false;
            boolean notifySet = doc.get("notify") instanceof Map<?, ?>;
            if (notifySet) {
                Map<String, Object> n = (Map<String, Object>) doc.get("notify");
                roles.addAll(strings(n.get("roles")));
                users.addAll(strings(n.get("users")));
                email = Boolean.parseBoolean(String.valueOf(n.getOrDefault("email", "false")));
            } else if (doc.containsKey("notify")) {
                problems.add("notify must be a map with roles, users and email");
            }
            if (!notifySet || roles.isEmpty() && users.isEmpty()) {
                roles.addAll(defaultRoles);
            }
            int smoke = 0;
            if (doc.get("smoke") != null) {
                try {
                    smoke = Integer.parseInt(String.valueOf(doc.get("smoke")).trim());
                } catch (NumberFormatException e) {
                    problems.add("smoke must be a whole number");
                }
                if (smoke < 0 || smoke > smokeMax) {
                    problems.add("smoke must be between 0 and " + smokeMax + " (drishti.loads.smoke-max)");
                    smoke = 0;
                }
            }
            Map<String, Expect> expect = new LinkedHashMap<>();
            if (doc.get("expect") instanceof Map<?, ?> e) {
                e.forEach((k, v) -> {
                    String kind = String.valueOf(k);
                    if (!(v instanceof Map<?, ?> x)) {
                        problems.add("expect." + kind + " must be a map with by (HH:mm), zone and calendar");
                        return;
                    }
                    String by = String.valueOf(x.get("by") == null ? "" : x.get("by")).trim();
                    String zone = String.valueOf(x.get("zone") == null ? "" : x.get("zone")).trim();
                    String cal = String.valueOf(x.get("calendar") == null ? "" : x.get("calendar")).trim();
                    try {
                        LocalTime.parse(by);
                    } catch (DateTimeException ex) {
                        problems.add("expect." + kind + ".by must be a time like 19:00");
                        return;
                    }
                    try {
                        if (!zone.isEmpty()) {
                            ZoneId.of(zone);
                        }
                    } catch (DateTimeException ex) {
                        problems.add("expect." + kind + ".zone '" + zone + "' is not a time zone");
                        return;
                    }
                    try {
                        if (!cal.isEmpty()) {
                            BusinessCalendar.of(cal);
                        }
                    } catch (RuntimeException ex) {
                        problems.add("expect." + kind + ".calendar '" + cal + "' is not a business-day calendar");
                        return;
                    }
                    expect.put(kind, new Expect(by, zone, cal));
                });
            } else if (doc.containsKey("expect") && doc.get("expect") != null) {
                problems.add("expect must be a map from kind to {by, zone, calendar}");
            }
            if (!problems.isEmpty() && !lenient) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, String.join("; ", problems));
            }
            return new LoadsConfig(roles, users, email, smoke, expect, origin);
        }

        private static List<String> strings(Object o) {
            return o instanceof List<?> l ? l.stream().map(String::valueOf).map(String::trim).filter(s -> !s.isEmpty()).toList() : List.of();
        }

        /** Saves the whole override (only what it says replaces the pack's own sections). */
        public void write(String pack, Map<String, Object> doc) {
            if (store != null) {
                try {
                    String text = HEADER + yaml.writeValueAsString(doc);
                    String version = store.get(key(pack)).map(com.ash.drishti.identity.content.ContentStore.Item::version).orElse(null);
                    store.put(key(pack), text.getBytes(java.nio.charset.StandardCharsets.UTF_8), version);   // another node's save meanwhile: 409 DRS-5057
                    return;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            try {
                Path f = file(pack);
                Files.createDirectories(f.getParent());
                Path tmp = com.ash.drishti.common.TempFiles.createFile(f.getParent(), pack + "-", ".tmp");
                Files.writeString(tmp, HEADER + yaml.writeValueAsString(doc));
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        public boolean reset(String pack) {
            if (store != null) {
                return store.deleteLatest(key(pack));
            }
            try {
                return Files.deleteIfExists(file(pack));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
