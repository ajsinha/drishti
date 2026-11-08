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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Push rows into a Drishti lake through the data inbox (docs/guides/DATA_LAKE_GUIDE.md section 3.4), with the JDK alone.
 *
 * <pre>
 * javac PushInbox.java
 * java PushInbox &lt;data&gt;/dataload/inbox trade_endur 2026-11-06 rows.jsonl lake=risklake pack=trading kind=trade connector=trade-endur key=TRADE_ID
 * </pre>
 *
 * The rows (one JSON object a line) are written gzipped into &lt;dataload&gt;/staging/ and renamed into the inbox when complete; the
 * .ready file, with the given keys, mode replace, the SHA-256 and the row count, is written last the same way. Your program passes its
 * own JSON lines to {@link #push}.
 */
public final class PushInbox {

    private PushInbox() {
    }

    /** One table, one business date: inbox/&lt;table&gt;.&lt;day&gt;.jsonl.gz, then its .ready with {@code keys} (lake, pack, kind, ...). */
    public static Path push(Path inbox, String table, String day, Iterable<String> jsonLines, Map<String, String> keys) throws IOException {
        Path staging = inbox.resolveSibling("staging");                 // beside the inbox: the same file system, so the move is one rename
        Files.createDirectories(inbox);
        Files.createDirectories(staging);
        String name = table + "." + day + ".jsonl.gz";
        Path tmp = staging.resolve(name);
        long rows = 0;
        try (OutputStream out = Files.newOutputStream(tmp);
             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(out, 1 << 16), StandardCharsets.UTF_8))) {
            for (String line : jsonLines) {
                w.write(line);
                w.write('\n');
                rows++;
            }
        }
        Map<String, String> note = new LinkedHashMap<>(keys);
        note.putIfAbsent("mode", "replace");                              // a re-run of the day replaces it
        note.put("sha256", sha256(tmp));
        note.put("rows", Long.toString(rows));
        Path data = Files.move(tmp, inbox.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        StringBuilder ready = new StringBuilder();
        note.forEach((k, v) -> ready.append(k).append(": ").append(v).append('\n'));
        Path marker = staging.resolve(name + ".ready");
        Files.writeString(marker, ready);
        Files.move(marker, inbox.resolve(name + ".ready"), StandardCopyOption.ATOMIC_MOVE);   // last: the inbox takes the pair from now on
        return data;
    }

    static String sha256(Path file) throws IOException {
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("usage: java PushInbox <inbox> <table> <YYYY-MM-DD> <rows.jsonl> [key=value ...]   (lake=, pack=, kind=, connector=, key=, mode=)");
            System.exit(2);
        }
        Map<String, String> keys = new LinkedHashMap<>();
        for (int i = 4; i < args.length; i++) {
            String[] kv = args[i].split("=", 2);
            keys.put(kv[0], kv[1]);
        }
        List<String> lines;
        try (InputStream in = Files.newInputStream(Path.of(args[3]))) {
            lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().filter(l -> !l.isBlank()).toList();
        }
        Path data = push(Path.of(args[0]), args[1], args[2], lines, keys);
        System.out.println("pushed " + data + " and " + data.getFileName() + ".ready (" + lines.size() + " rows)");
    }
}
