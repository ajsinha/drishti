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
package com.ash.drishti.server.playground;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The playgrounds' headless LibreOffice harness (VAR_PLAYGROUND.md section 12): a workbook recalculated by {@code soffice --headless} with
 * a private profile that recalculates every formula on load, and the recalculated cells read back. Every later playground's drill test
 * uses it. Skips where LibreOffice is absent (the Windows QA machine). Only one LibreOffice runs at a time on the machine: the run holds
 * {@code flock} on {@code DRISHTI_SOFFICE_LOCK} (default {@code <tmp>/drishti-soffice.lock}) when the {@code flock} command exists.
 */
public final class LibreOfficeRecalc {

    private static final Pattern CELL = Pattern.compile("<c r=\"([A-Z]+[0-9]+)\"([^>]*?)(?:/>|>(.*?)</c>)", Pattern.DOTALL);
    private static final Pattern VALUE = Pattern.compile("<v>(.*?)</v>", Pattern.DOTALL);
    private static final Pattern INLINE = Pattern.compile("<t[^>]*>(.*?)</t>", Pattern.DOTALL);
    private static final Pattern SI = Pattern.compile("<si>(.*?)</si>", Pattern.DOTALL);
    private static final Pattern SHEET = Pattern.compile("<sheet [^>]*name=\"([^\"]+)\"[^>]*r:id=\"([^\"]+)\"");
    private static final Pattern REL = Pattern.compile("<Relationship [^>]*Id=\"([^\"]+)\"[^>]*Target=\"([^\"]+)\"");
    private static final Pattern REL2 = Pattern.compile("<Relationship [^>]*Target=\"([^\"]+)\"[^>]*Id=\"([^\"]+)\"");

    private LibreOfficeRecalc() {}

    /** True when {@code soffice} can run here (not Windows, on the PATH). */
    public static boolean available() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return false;
        }
        return which("soffice") != null;
    }

    private static String which(String cmd) {
        for (String d : System.getenv().getOrDefault("PATH", "").split(java.io.File.pathSeparator)) {
            Path p = Path.of(d, cmd);
            if (Files.isExecutable(p)) {
                return p.toString();
            }
        }
        return null;
    }

    /**
     * Recalculates {@code xlsx} (every formula, on load) and returns the file LibreOffice saved, under {@code work}. Its private profile is
     * {@code work/lo-profile}, made on the first run.
     */
    public static Path recalc(Path xlsx, Path work) throws IOException, InterruptedException {
        Path profile = work.resolve("lo-profile");
        Path user = Files.createDirectories(profile.resolve("user"));
        Path reg = user.resolve("registrymodifications.xcu");
        if (!Files.exists(reg)) {
            Files.writeString(reg, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <oor:items xmlns:oor="http://openoffice.org/2001/registry" xmlns:xs="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                    <item oor:path="/org.openoffice.Office.Calc/Formula/Load"><prop oor:name="OOXMLRecalcMode" oor:op="fuse"><value>0</value></prop></item>
                    <item oor:path="/org.openoffice.Office.Calc/Formula/Load"><prop oor:name="ODFRecalcMode" oor:op="fuse"><value>0</value></prop></item>
                    </oor:items>
                    """, StandardCharsets.UTF_8);
        }
        Path out = Files.createDirectories(work.resolve("lo-out-" + System.nanoTime()));
        List<String> cmd = new ArrayList<>();
        String flock = which("flock");
        if (flock != null) {
            String lock = System.getenv().getOrDefault("DRISHTI_SOFFICE_LOCK", Path.of(System.getProperty("java.io.tmpdir"), "drishti-soffice.lock").toString());
            cmd.addAll(List.of(flock, lock));
        }
        cmd.addAll(List.of("soffice", "-env:UserInstallation=" + profile.toAbsolutePath().toUri(), "--headless", "--norestore", "--nologo",
                "--convert-to", "xlsx:Calc MS Excel 2007 XML", "--outdir", out.toAbsolutePath().toString(), xlsx.toAbsolutePath().toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(work.resolve("lo.log").toFile()).start();
        if (!p.waitFor(15, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IllegalStateException("LibreOffice did not finish in 15 minutes");
        }
        Path saved = out.resolve(xlsx.getFileName().toString());
        if (p.exitValue() != 0 || !Files.exists(saved)) {
            throw new IllegalStateException("LibreOffice failed (" + p.exitValue() + "): " + Files.readString(work.resolve("lo.log")));
        }
        return saved;
    }

    /** One sheet's cells: a number as a Double, text as a String (formula results included); empty cells left out. */
    public static Map<String, Object> cells(Path xlsx, String sheetName) throws IOException {
        Map<String, String> parts = new HashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(xlsx)))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().endsWith(".xml") || e.getName().endsWith(".rels")) {
                    parts.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        String rid = null;
        Matcher sm = SHEET.matcher(parts.get("xl/workbook.xml"));
        while (sm.find()) {
            if (unescape(sm.group(1)).equals(sheetName)) {
                rid = sm.group(2);
            }
        }
        if (rid == null) {
            throw new IllegalArgumentException("no sheet " + sheetName);
        }
        String rels = parts.get("xl/_rels/workbook.xml.rels");
        String target = null;
        Matcher rm = REL.matcher(rels);
        while (rm.find()) {
            if (rm.group(1).equals(rid)) {
                target = rm.group(2);
            }
        }
        if (target == null) {
            Matcher r2 = REL2.matcher(rels);
            while (r2.find()) {
                if (r2.group(2).equals(rid)) {
                    target = r2.group(1);
                }
            }
        }
        String sheet = parts.get(target.startsWith("/") ? target.substring(1) : "xl/" + target);
        List<String> shared = new ArrayList<>();
        if (parts.containsKey("xl/sharedStrings.xml")) {
            Matcher si = SI.matcher(parts.get("xl/sharedStrings.xml"));
            while (si.find()) {
                StringBuilder b = new StringBuilder();
                Matcher t = INLINE.matcher(si.group(1));
                while (t.find()) {
                    b.append(unescape(t.group(1)));
                }
                shared.add(b.toString());
            }
        }
        Map<String, Object> out = new HashMap<>();
        Matcher c = CELL.matcher(sheet);
        while (c.find()) {
            String attrs = c.group(2);
            String body = c.group(3);
            if (body == null) {
                continue;
            }
            String type = attr(attrs, "t");
            if ("inlineStr".equals(type)) {
                Matcher t = INLINE.matcher(body);
                out.put(c.group(1), t.find() ? unescape(t.group(1)) : "");
                continue;
            }
            Matcher v = VALUE.matcher(body);
            if (!v.find()) {
                continue;
            }
            String raw = unescape(v.group(1));
            if ("s".equals(type)) {
                out.put(c.group(1), shared.get(Integer.parseInt(raw.trim())));
            } else if ("str".equals(type) || "e".equals(type)) {
                out.put(c.group(1), raw);
            } else if ("b".equals(type)) {
                out.put(c.group(1), raw.equals("1") ? 1.0 : 0.0);
            } else {
                out.put(c.group(1), Double.parseDouble(raw));
            }
        }
        return out;
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\s" + name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static String unescape(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    /** {@code bytes} written under {@code work} as {@code name}. */
    public static Path write(Path work, String name, byte[] bytes) {
        try {
            Files.createDirectories(work);
            return Files.write(work.resolve(name), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
