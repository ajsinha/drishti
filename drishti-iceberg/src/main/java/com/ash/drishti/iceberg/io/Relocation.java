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
package com.ash.drishti.iceberg.io;

import com.ash.drishti.lakeio.LocalPaths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The {@code relocate} setting: {@code <old> -> <new>; <old2> -> <new2>}, each side a folder as one writes it ({@code /data/ice1},
 * {@code C:\lake}, {@code \\fs\share\lake}) or a URI ({@code file:/data/ice1}, {@code file:///C:/lake}, {@code s3://bucket/wh}). Both sides
 * are brought to the one form a table's metadata names its files in ({@code file:/data/ice1}, {@code file:/C:/lake},
 * {@code file://fs/share/lake}, {@code s3://bucket/wh}), and so is every path looked up, so a plain folder matches the {@code file:} URIs of
 * the metadata (QA3B-04: only the {@code file:} form worked, and the plain form the docs give was ignored without a word). A prefix matches
 * whole folders only ({@code /data/ice1} does not match {@code /data/ice10}). A pair that cannot be read is refused, naming it.
 */
public final class Relocation {

    /** {@code C:}, {@code C:/…}, also after a leading slash. */
    private static final Pattern DRIVE = Pattern.compile("^/?([A-Za-z]):(/.*)?$");
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*)://.+");

    /** One pair, both sides in the metadata's form. */
    public record Pair(String from, String to) {}

    private static final Relocation NONE = new Relocation(List.of());

    private final List<Pair> pairs;

    private Relocation(List<Pair> pairs) {
        this.pairs = List.copyOf(pairs);
    }

    public static Relocation none() {
        return NONE;
    }

    /** The pairs of {@code spec} (blank: none); throws {@link IllegalArgumentException} naming a pair that cannot be read. */
    public static Relocation parse(String spec) {
        if (spec == null || spec.isBlank()) {
            return NONE;
        }
        List<Pair> out = new ArrayList<>();
        for (String raw : unquote(spec).split(";")) {
            String pair = unquote(raw);                                    // relocate: '/a -> /b' as Health gives it, quotes and all
            if (pair.isEmpty()) {
                continue;
            }
            int arrow = pair.indexOf("->");
            if (arrow < 0 || pair.indexOf("->", arrow + 2) >= 0) {
                throw refused(pair, "it needs one ' -> ' between the old folder and the new one");
            }
            String from = unquote(pair.substring(0, arrow));
            String to = unquote(pair.substring(arrow + 2));
            if (from.isEmpty() || to.isEmpty()) {
                throw refused(pair, from.isEmpty() ? "the old folder is missing" : "the new folder is missing");
            }
            out.add(new Pair(canonical(from, pair), canonical(to, pair)));
        }
        return new Relocation(out);
    }

    /** The pairs from a {@code Map} of prefixes (tests and tools), each side brought to the metadata's form. */
    public static Relocation of(java.util.Map<String, String> prefixes) {
        List<Pair> out = new ArrayList<>();
        prefixes.forEach((f, t) -> out.add(new Pair(canonical(f, f + " -> " + t), canonical(t, f + " -> " + t))));
        return new Relocation(out);
    }

    public List<Pair> pairs() {
        return pairs;
    }

    public boolean isEmpty() {
        return pairs.isEmpty();
    }

    /** {@code path} with the first matching old folder replaced by its new one; else {@code path} as it is. */
    public String apply(String path) {
        if (pairs.isEmpty() || path == null) {
            return path;
        }
        String p = comparable(path);
        for (Pair e : pairs) {
            if (under(p, e.from())) {
                return e.to() + p.substring(e.from().length());
            }
        }
        return path;
    }

    /** Whether a pair's old folder holds {@code path}. */
    public boolean matches(String path) {
        String p = comparable(path);
        return pairs.stream().anyMatch(e -> under(p, e.from()));
    }

    /** Whether a pair's new folder holds {@code path} (a read already relocated). */
    public boolean targets(String path) {
        String p = comparable(path);
        return pairs.stream().anyMatch(e -> under(p, e.to()));
    }

    /** {@code path} in the form the pairs are kept in (a {@code file:} URI canonical, an S3 scheme as {@code s3://}); else as it is. */
    public static String comparable(String path) {
        try {
            return canonical(path, path);
        } catch (IllegalArgumentException e) {
            return path;
        }
    }

    /** How a folder is written in a {@code relocate} the reader copies: a plain path for local disk (forward slashes), else the URI. */
    public static String shown(String path) {
        String c = comparable(path);
        if (c.startsWith("file://")) {
            return "//" + c.substring("file://".length());                // a share: //fs/share/lake
        }
        if (c.startsWith("file:")) {
            String p = c.substring("file:".length());
            return DRIVE.matcher(p).matches() ? p.substring(1) : p;          // /C:/lake to C:/lake
        }
        return c;
    }

    /**
     * The {@code relocate} that reads a table written under {@code was} from where it is now, {@code now}: the folders where the two differ, the
     * part they share at the end (the domain and the table's folder) left off, so one pair covers every table moved together.
     */
    public static Optional<String> suggest(String was, String now) {
        if (was == null || now == null) {
            return Optional.empty();
        }
        String a = shown(was);
        String b = shown(now);
        if (a.equals(b)) {
            return Optional.empty();
        }
        List<String> as = new ArrayList<>(List.of(a.split("/", -1)));
        List<String> bs = new ArrayList<>(List.of(b.split("/", -1)));
        String last = null;
        while (as.size() > 1 && bs.size() > 1 && as.get(as.size() - 1).equals(bs.get(bs.size() - 1))) {
            last = as.remove(as.size() - 1);
            bs.remove(bs.size() - 1);
        }
        String from = String.join("/", as);
        String to = String.join("/", bs);
        if (last != null && (bare(from) || bare(to))) {
            from = from + "/" + last;                                         // never a root or a drive alone: one folder more on both sides
            to = to + "/" + last;
        }
        return Optional.of(from + " -> " + to);
    }

    private static boolean bare(String folder) {
        return folder.isEmpty() || folder.endsWith(":") || folder.equals("/") || folder.endsWith("//");
    }

    private static boolean under(String path, String folder) {
        return path.startsWith(folder) && (path.length() == folder.length() || path.charAt(folder.length()) == '/' || folder.endsWith("/"));
    }

    private static String unquote(String s) {
        String t = s.strip();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))
                && t.indexOf(t.charAt(0), 1) == t.length() - 1) {
            t = t.substring(1, t.length() - 1).strip();
        }
        return t;
    }

    private static IllegalArgumentException refused(String pair, String why) {
        return new IllegalArgumentException("relocate: '" + pair + "' cannot be read: " + why + ". Write it as <old folder> -> <new folder>"
                + " (for example /data/ice1 -> /data/ice2, C:\\lake -> D:\\lake or s3://bucket/wh -> /copy/wh); separate several pairs with ;");
    }

    /** One side in the metadata's form; throws naming the pair when it is neither an absolute folder nor a URI. */
    static String canonical(String side, String pair) {
        String s = side.strip();
        java.util.regex.Matcher scheme = SCHEME.matcher(s);
        boolean file = s.regionMatches(true, 0, "file:", 0, 5);
        if (!file && scheme.matches() && !DRIVE.matcher(s.replace('\\', '/')).matches()) {
            String sc = scheme.group(1).toLowerCase(Locale.ROOT);
            String rest = s.substring(s.indexOf("://") + 3);
            if (sc.equals("s3a") || sc.equals("s3n")) {
                sc = "s3";                                                    // one bucket, whichever Hadoop name the writer used
            }
            return sc + "://" + strip(rest);
        }
        String local;
        if (file) {
            local = LocalPaths.toLocal(LocalPaths.decodeUri(s), false);         // file:///x, file://localhost/x, file:/x: /x; a share //fs/…
        } else {
            local = s;
        }
        local = local.replace('\\', '/');
        java.util.regex.Matcher d = DRIVE.matcher(local);
        if (d.matches()) {
            String tail = d.group(2) == null ? "/" : d.group(2);
            return "file:/" + d.group(1).toUpperCase(Locale.ROOT) + ":" + strip(tail.replaceAll("/{2,}", "/"));
        }
        if (local.startsWith("//") && local.length() > 2 && local.charAt(2) != '/') {
            return "file://" + strip(local.substring(2).replaceAll("/{2,}", "/"));       // a share: //fs/share/lake
        }
        if (local.startsWith("/")) {
            return "file:" + strip(local.replaceAll("/{2,}", "/"));
        }
        throw refused(pair, "'" + side.strip() + "' is not an absolute folder or a URI (file:, s3://)");
    }

    private static String strip(String p) {
        String s = p;
        while (s.length() > 1 && s.endsWith("/") && !s.matches("^/?[A-Za-z]:/$")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    @Override
    public String toString() {
        return String.join("; ", pairs.stream().map(p -> shown(p.from()) + " -> " + shown(p.to())).toList());
    }
}
