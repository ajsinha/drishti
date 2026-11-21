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
package com.ash.drishti.lakehost;

import com.ash.lake.LakeOptions;
import com.ash.lake.LakeRuntime;
import com.ash.lake.common.RowFeed;
import com.ash.lake.memory.store.MemoryStorageProvider;
import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The one {@link LakeRuntime} of a Drishti process. The server builds it once at start ({@link #build}: Drishti's catalog, the
 * process's settings, the {@code mem://} storage) and installs it ({@link #use}); every call into Drishti Lake is handed
 * {@link #runtime()}. A process that installs none (a command-line tool, a unit test) gets one built from its own settings, rebuilt
 * when the settings it was built from change.
 *
 * <p>Settings: Drishti Lake's own names ({@code lake.*}, environment {@code LAKE_*}) for everything that is the lake's; the four
 * folders Drishti already names ({@code drishti.home}, {@code drishti.data.dir}, {@code drishti.config.dir}, {@code drishti.tmp-dir})
 * stand for the lake's when its own are not set.
 */
public final class DrishtiLake {

    /** Every option of Drishti Lake a Drishti process may set. */
    static final List<String> OPTIONS = List.of(LakeOptions.HOME, LakeOptions.CONFIG_DIR, LakeOptions.DATA_DIR, LakeOptions.CONNECTORS_DIR,
            LakeOptions.LAKES_DIR, LakeOptions.TMP_DIR, LakeOptions.ROW_GROUP_KB, LakeOptions.ROW_GROUP_MB, LakeOptions.PAGE_KB,
            LakeOptions.PAGE_INDEX, LakeOptions.FOOTER_CACHE_MB, LakeOptions.DELETE_CACHE_ROWS, LakeOptions.MANIFEST_CACHE_BYTES,
            LakeOptions.LOADER_TRACE, LakeOptions.ARCHIVE_MAX_MB, LakeOptions.ARCHIVE_MAX_RATIO, LakeOptions.ARCHIVE_MAX_MEMBERS,
            LakeOptions.S3_ENDPOINT, LakeOptions.S3_REGION, LakeOptions.S3_PATH_STYLE, LakeOptions.S3_READ_BLOCK_KB, LakeOptions.BUSINESS_ZONE,
            LakeOptions.SETTINGS_FILE);

    /** The lake's folders that Drishti's own folder settings stand for. */
    static final Map<String, String> DRISHTI_FOLDERS = Map.of(LakeOptions.HOME, "drishti.home", LakeOptions.DATA_DIR, "drishti.data.dir",
            LakeOptions.CONFIG_DIR, "drishti.config.dir", LakeOptions.TMP_DIR, "drishti.tmp-dir");

    private record Built(Map<String, String> options, LakeRuntime runtime) {}

    private static volatile LakeRuntime installed;
    private static volatile Built fromProcess;

    private DrishtiLake() {
    }

    /** The process's runtime: the one installed, else one of the process's settings. */
    public static LakeRuntime runtime() {
        LakeRuntime r = installed;
        if (r != null) {
            return r;
        }
        Map<String, String> options = options(DrishtiLake::process);
        Built b = fromProcess;
        if (b == null || !b.options().equals(options)) {
            b = new Built(options, build(options, DrishtiLake::process, DrishtiLake::processNames));
            fromProcess = b;
        }
        return b.runtime();
    }

    /** Installs the process's runtime (the server, once at start); null returns to the process's settings. */
    public static void use(LakeRuntime runtime) {
        installed = runtime;
    }

    /** A runtime of Drishti's catalog over these settings, with the in-memory lake's storage ({@code mem://}) of its own. */
    public static LakeRuntime build(Function<String, String> lookup, Supplier<Collection<String>> names) {
        return build(options(lookup), lookup, names);
    }

    private static LakeRuntime build(Map<String, String> options, Function<String, String> lookup, Supplier<Collection<String>> names) {
        LakeOptions.Builder o = LakeOptions.builder();
        options.forEach(o::set);
        return LakeRuntime.builder().options(o.build()).catalog(new DrishtiLakeCatalog()).settings(lookup, names)
                .storage(new MemoryStorageProvider()).build();
    }

    /** The lake's options these settings give: its own names first, else the Drishti folder that stands for it. */
    static Map<String, String> options(Function<String, String> lookup) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : OPTIONS) {
            String v = value(lookup, k);
            if (v == null && DRISHTI_FOLDERS.containsKey(k)) {
                v = value(lookup, DRISHTI_FOLDERS.get(k));
            }
            if (v != null) {
                out.put(k, v);
            }
        }
        return out;
    }

    /** The business zone of the process's runtime. */
    public static ZoneId zone() {
        return runtime().options().businessZone();
    }

    /** Drishti Lake's row feed (the load rules: each row's file and business date) in the runtime's business zone. */
    public static RowFeed rowFeed(String input, LocalDate asOf) throws IOException {
        return RowFeed.open(input, asOf, zone());
    }

    /** As {@link #rowFeed} over a stream of one table and one date. */
    public static RowFeed rowStream(Reader in, LocalDate date) throws IOException {
        return RowFeed.stream(in, date, zone());
    }

    private static String value(Function<String, String> lookup, String key) {
        String v = lookup.apply(key);
        if (v == null || v.isBlank()) {
            v = lookup.apply(com.ash.tools.settings.SettingsFile.envName(key));
        }
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static String process(String key) {
        String v = System.getProperty(key);
        return v != null ? v : System.getenv(key);
    }

    private static Collection<String> processNames() {
        Set<String> out = new LinkedHashSet<>(System.getProperties().stringPropertyNames());
        out.addAll(System.getenv().keySet());
        return out;
    }
}
