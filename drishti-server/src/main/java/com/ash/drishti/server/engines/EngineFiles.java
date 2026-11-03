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
package com.ash.drishti.server.engines;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The site's engine files, {@code config/engines/<id>.yaml} (DRISHTI_ENGINES_DIR; docs/architecture/COMPUTE_ENGINES.md section 8, "Writing
 * the file"): the server writes only the switch ({@code enabled}, keeping its environment variable) and {@code changed}, keeping every comment
 * and every other key, atomically (a temporary file in the same folder, then a rename), creating the file from the jar's copy when the site
 * has none. A file changed on disk since the page read it, or a folder that cannot be written, is refused with {@code 409 DRS-1023}.
 */
public final class EngineFiles {

    private static final Pattern ENGINE_SOURCE = Pattern.compile("(?s).*engines/[a-z0-9-]+\\.yaml\\].*");
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([A-Za-z0-9_.-]+):([^}]*)}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;
    private final Function<String, String> envVars;

    /**
     * @param dir the folder ({@code drishti.engines.dir})
     * @param envVars the environment variables (System::getenv; a test passes its own)
     */
    public EngineFiles(Path dir, Function<String, String> envVars) {
        this.dir = dir;
        this.envVars = envVars;
    }

    /** Whether a Spring property source's name is a site's engine file. */
    public static boolean isEngineFile(String sourceName) {
        String n = sourceName.replace('\\', '/');
        return n.startsWith("Config resource") && n.contains("file [") && ENGINE_SOURCE.matcher(n).matches();
    }

    public Path dir() {
        return dir;
    }

    public Path file(EngineCatalogue.Entry e) {
        return dir.resolve(e.file());
    }

    /** The file's checksum now ({@code ""} when the site has no file). */
    public String checksum(EngineCatalogue.Entry e) {
        Path f = file(e);
        try {
            return Files.isRegularFile(f) ? sha(Files.readAllBytes(f)) : "";
        } catch (IOException x) {
            return "";
        }
    }

    /** The switch as the site's file says it now (its environment variable, when the file names one and it is set), or null without a file. */
    public Boolean enabledInFile(EngineCatalogue.Entry e) {
        Path f = file(e);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            int at = switchLine(lines, e.id());
            if (at < 0) {
                return null;
            }
            String raw = value(lines.get(at));
            Matcher m = PLACEHOLDER.matcher(raw);
            if (m.matches()) {
                String set = envVars.apply(m.group(1));
                return Boolean.parseBoolean((set != null ? set : m.group(2)).strip());
            }
            return Boolean.parseBoolean(raw.replace("\"", "").replace("'", "").strip());
        } catch (IOException x) {
            return null;
        }
    }

    /**
     * Writes the switch and {@code changed} of one engine's file.
     *
     * @param expected the checksum the page read ({@code null}: not checked)
     * @return the checksums before and after
     */
    public String[] write(EngineCatalogue.Entry e, boolean on, String by, String at, String reason, String expected) {
        Path f = file(e);
        String before = checksum(e);
        if (expected != null && !expected.equals(before)) {
            throw new DrishtiException(ErrorCode.ENGINE_FILE_CONFLICT, "the file " + f + " was edited by hand since you opened this page; reload");
        }
        try {
            List<String> lines = Files.isRegularFile(f) ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8)) : shippedLines(e);
            List<String> out = edit(lines, e, on, by, at, reason);
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, e.id() + "-writing-", ".tmp");   // visible, beside the file (a rename within one folder)
            try {
                Files.writeString(tmp, String.join("\n", out) + "\n", StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException x) {
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException x) {
            throw new DrishtiException(ErrorCode.ENGINE_FILE_CONFLICT, "the engines' folder " + dir.toAbsolutePath().normalize()
                    + " cannot be written (" + x.getClass().getSimpleName() + "); make it writable or point DRISHTI_ENGINES_DIR at a folder that is");
        }
        return new String[] {before, checksum(e)};
    }

    /** The file's lines with the switch and {@code changed} of the engine replaced (inserted when missing); everything else as it was. */
    static List<String> edit(List<String> lines, EngineCatalogue.Entry e, boolean on, String by, String at, String reason) {
        List<String> out = new ArrayList<>(lines);
        int sw = switchLine(out, e.id());
        String changed = "{ by: " + quote(by) + ", at: " + quote(at) + ", reason: " + quote(reason) + " }";
        if (sw < 0) {
            int eng = indexOf(out, "  engines:", indexOf(out, "drishti:", 0));
            if (eng < 0) {
                out.add("drishti:");
                out.add("  engines:");
                eng = out.size() - 1;
            }
            out.add(eng + 1, "    " + e.id() + ":");
            out.add(eng + 2, "      enabled: " + on);
            out.add(eng + 3, "      changed: " + changed);
            return out;
        }
        String raw = value(out.get(sw));
        Matcher m = PLACEHOLDER.matcher(raw);
        String next = m.matches() ? "${" + m.group(1) + ":" + on + "}" : String.valueOf(on);
        out.set(sw, replaceValue(out.get(sw), next));
        int ch = -1;
        for (int i = sw + 1; i < out.size(); i++) {
            String l = out.get(i);
            if (l.isBlank() || l.strip().startsWith("#")) {
                continue;
            }
            if (indent(l) < 6) {
                break;
            }
            if (l.startsWith("      changed:")) {
                ch = i;
                break;
            }
        }
        if (ch >= 0) {
            out.set(ch, replaceValue(out.get(ch), changed));
        } else {
            out.add(sw + 1, "      changed: " + changed);
        }
        return out;
    }

    /** The line of {@code drishti.engines.<id>.enabled}, or -1. */
    static int switchLine(List<String> lines, String id) {
        int d = indexOf(lines, "drishti:", 0);
        int eng = d < 0 ? -1 : indexOf(lines, "  engines:", d);
        int me = eng < 0 ? -1 : indexOf(lines, "    " + id + ":", eng);
        if (me < 0) {
            return -1;
        }
        for (int i = me + 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isBlank() || l.strip().startsWith("#")) {
                continue;
            }
            if (indent(l) < 6) {
                return -1;
            }
            if (l.startsWith("      enabled:")) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOf(List<String> lines, String start, int from) {
        if (from < 0) {
            return -1;
        }
        for (int i = from; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.equals(start) || l.startsWith(start + " ") || l.startsWith(start + "#")) {
                return i;
            }
        }
        return -1;
    }

    private static int indent(String l) {
        int i = 0;
        while (i < l.length() && l.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    /** A {@code key: value   # comment} line's value. */
    static String value(String line) {
        String rest = line.substring(line.indexOf(':') + 1);
        int hash = commentAt(rest);
        return (hash < 0 ? rest : rest.substring(0, hash)).strip();
    }

    private static String replaceValue(String line, String value) {
        int colon = line.indexOf(':');
        String rest = line.substring(colon + 1);
        int hash = commentAt(rest);
        String comment = hash < 0 ? "" : rest.substring(hash);
        String head = line.substring(0, colon + 1) + " " + value;
        return comment.isEmpty() ? head : head + "   " + comment.strip();
    }

    /** Where a YAML comment starts in a value (a {@code #} after a space, outside quotes), or -1. */
    private static int commentAt(String s) {
        boolean dq = false;
        boolean sq = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' && !sq && (i == 0 || s.charAt(i - 1) != '\\')) {
                dq = !dq;
            } else if (c == '\'' && !dq) {
                sq = !sq;
            } else if (c == '#' && !dq && !sq && (i == 0 || s.charAt(i - 1) == ' ')) {
                return i;
            }
        }
        return -1;
    }

    private static String quote(String s) {
        try {
            return JSON.writeValueAsString(s == null ? "" : s);
        } catch (JsonProcessingException x) {
            return "\"\"";
        }
    }

    /** The jar's copy of the engine's file, the start of a site file that did not exist. */
    static List<String> shippedLines(EngineCatalogue.Entry e) throws IOException {
        try (InputStream in = EngineFiles.class.getClassLoader().getResourceAsStream("engines/" + e.file())) {
            if (in == null) {
                return new ArrayList<>(List.of("drishti:", "  engines:"));
            }
            return new ArrayList<>(new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        }
    }

    static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b), 0, 12);
        } catch (NoSuchAlgorithmException x) {
            throw new IllegalStateException(x);
        }
    }
}
