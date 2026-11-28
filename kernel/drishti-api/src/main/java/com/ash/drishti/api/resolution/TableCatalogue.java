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
package com.ash.drishti.api.resolution;

import java.nio.file.Path;

/**
 * The one place a table's symbolic name becomes a location (owner rule 2026-11-27, docs/admin/TABLE_CATALOGUE.md). Code, packs, Sutras,
 * engine inputs and contracts name a table only by its symbolic name ({@code trading.trade}, {@code market.scenario-pv}); the lake file
 * ({@code config/lakes/<lake>.yaml}) says, per table, where it is (an absolute {@code uri:}), its format and its contract. A table with no
 * {@code uri:} sits where the lake's own layout puts it, {@code <root>/<domain>/<folder>}: that join is made here and nowhere else (the
 * guard {@code TableCatalogueGuardTest} fails a main source that joins a root, a namespace and a table itself).
 *
 * <p>A connector's expanded settings ({@code root}, {@code domain}, {@code table.<kind>}) are the form the readers are handed; this turns
 * them back into the one location, so Admin, the readers and the loaders agree on what a table's address is.
 */
public final class TableCatalogue {

    /** How a location was found: the lake file's own {@code uri:} for the table, or the lake's layout. */
    public static final String CONFIGURED = "configured";
    /** The location is the lake's root and the table's path ({@code <root>/<domain>/<folder>}). */
    public static final String BY_CONVENTION = "fallback";

    private TableCatalogue() {
    }

    /**
     * A resolved table.
     *
     * @param name     the symbolic name ({@code trading.trade}, or the kind when only that is known)
     * @param uri      where it is: an absolute local path or a URI ({@code s3://bucket/folder})
     * @param format   {@code delta}, {@code iceberg} or {@code files}; empty when not known here
     * @param contract the contract it is read by ({@code trade@1}); empty: its kind's own
     * @param how      {@link #CONFIGURED} or {@link #BY_CONVENTION}
     */
    public record Resolved(String name, String uri, String format, String contract, String how) {
    }

    /**
     * The location of the table whose folder is {@code folder} under {@code domain} under {@code root}: the lake's layout, joined here.
     * A blank {@code domain} puts the folder directly under the root. A local root comes back as an absolute normalised path, an
     * object-store root ({@code s3://...}, {@code mem://...}) as written with no trailing slash.
     */
    public static String locate(String root, String domain, String folder) {
        String joined = join(root, domain, folder);
        return joined.contains("://") ? joined : Path.of(joined).toAbsolutePath().normalize().toString();
    }

    /**
     * The same location as written, as one string with nothing normalised (a root such as {@code file:/data} or a relative folder stays as
     * the caller gave it): for the writers that hand the location to a storage layer which reads its own schemes.
     */
    public static String join(String root, String domain, String folder) {
        String r = root == null ? "" : root.strip();
        return (r.endsWith("/") && r.length() > 1 ? r.substring(0, r.length() - 1) : r)
                + (domain == null || domain.isBlank() ? "" : "/" + domain.strip().replaceAll("^/+|/+$", ""))
                + (folder == null || folder.isBlank() ? "" : "/" + folder.strip().replaceAll("^/+|/+$", ""));
    }

    /** As {@link #locate(String, String, String)}, as a local {@link Path} (the root and domain of a folder-based reader or loader). */
    public static Path locateLocal(String root, String domain) {
        return Path.of(locate(root, domain, ""));
    }

    /** The folder a path-based Iceberg namespace ({@code risk.marts}) is stored in under the root: dots become folders. */
    public static String namespaceFolder(String namespace) {
        return namespace == null ? "" : namespace.replace('.', '/');
    }

    /**
     * The table of {@code kind} as a connector's expanded settings address it ({@code root}, {@code domain}, {@code table.<kind>}), or
     * null when the settings carry no root (a catalogue-bound table has no path). {@code configured} says the lake file named the table's
     * own {@code uri:}; the settings alone cannot tell, so the caller passes what it knows.
     */
    public static Resolved of(String name, String kind, java.util.Map<String, String> settings, String format, String contract,
            boolean configured) {
        String root = settings.get("root");
        if (root == null || root.isBlank()) {
            return null;
        }
        String uri = locate(root, settings.getOrDefault("domain", ""), settings.getOrDefault("table." + kind, kind));
        return new Resolved(name, uri, format == null ? "" : format, contract == null ? "" : contract, configured ? CONFIGURED : BY_CONVENTION);
    }
}
