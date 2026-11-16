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
package com.ash.drishti.lakeload;

import com.ash.drishti.iceberg.io.FileAccess;
import com.ash.drishti.iceberg.io.Relocation;
import com.ash.drishti.lakeio.S3Settings;
import com.ash.drishti.lakeload.Inputs.InputFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The loads one run makes when its input holds archives (DATA_GUIDE.md section 2.7): every archive listed and checked by the archive
 * rule ({@link Archives}) before anything is read, then
 *
 * <ul>
 *   <li>one table configured ({@code target}): one load, the archives' members among the plain files; an archive's folders, if any, must
 *       all be that table's;</li>
 *   <li>{@code datasets:}: one load per dataset that the archives hold (each folder, or a folderless archive named after its file or
 *       {@code source.dataset}), into its own table; a dataset the configuration does not name is refused, every one listed. With more
 *       than one, every dataset is first read and checked as a dry run ({@code source.archive.validate: rows}, the default), so a
 *       dataset that would fail stops the run before any commit; then each is loaded in name order, and the run reports each one's
 *       rows, files and version.</li>
 * </ul>
 */
final class DatasetLoads {

    /** One load: its dataset (null: the one table), its configuration and its input. */
    record Load(String dataset, LoadConfig cfg, List<InputFile> files) {}

    /** What a load came to: its exit code and summary. */
    record Result(int code, ObjectNode summary) {}

    private DatasetLoads() {}

    /** The loads, or null when the input holds no archive and no {@code datasets:} is configured (the load as it always was). */
    static List<Load> plan(LoadConfig cfg, PrintStream out, boolean quiet) throws IOException {
        List<InputFile> resolved;
        try (FileAccess io = new FileAccess(S3Settings.from(cfg.source().storage()), Relocation.none(), null)) {
            resolved = Inputs.resolve(cfg.source().files(), cfg.source().recursive(), io);
            boolean any = resolved.stream().anyMatch(InputFile::isArchive);
            if (!any && cfg.datasets().isEmpty()) {
                return null;
            }
            List<InputFile> plain = new ArrayList<>();
            List<Archives.Plan> plans = new ArrayList<>();
            for (InputFile f : resolved) {
                if (!f.isArchive()) {
                    plain.add(f);
                    continue;
                }
                Archives.Plan p = Archives.plan(io, f.path(), f.size(), cfg.archive().dataset(), cfg.archive().limits());
                if (!quiet) {
                    p.notes().forEach(n -> out.println("  " + p.archiveName() + ": " + n));
                }
                plans.add(p);
            }
            for (Archives.Plan p : plans) {
                if (p.kind() != Archives.Kind.ZIP && cfg.source().asOf() != null) {
                    throw new Archives.ArchiveException(p.archiveName() + ": a .tar archive names its own tables and dates (its <table>/<YYYY-MM-DD>/ "
                            + "folders): source.as-of is refused for it");
                }
            }
            if (cfg.datasets().isEmpty()) {
                String table = cfg.domainAndTable()[1];
                List<InputFile> files = new ArrayList<>(plain);
                String only = cfg.archive().dataset();
                for (Archives.Plan p : plans) {
                    if (p.folders() && only != null && !only.isBlank()) {        // a tar's one table this load is for (the others: other loads)
                        p.datasets().getOrDefault(only, List.of()).forEach(m -> files.add(InputFile.of(p, m)));
                        continue;
                    }
                    if (p.folders()) {
                        p.require(Set.of(table));
                    }
                    p.members().forEach(m -> files.add(InputFile.of(p, m)));
                }
                files.sort(Comparator.comparing(InputFile::path));
                return List.of(new Load(null, cfg, files));
            }
            if (!plain.isEmpty()) {
                throw new Archives.ArchiveException("with datasets:, every input is an archive (its folders, or its name, say the dataset): "
                        + plain.get(0).path() + " is not");
            }
            Map<String, List<InputFile>> byDataset = new LinkedHashMap<>();
            for (Archives.Plan p : plans) {
                p.require(cfg.datasets().keySet());
            }
            for (Archives.Plan p : plans) {
                p.datasets().forEach((d, ms) -> ms.forEach(m -> byDataset.computeIfAbsent(d, k -> new ArrayList<>()).add(InputFile.of(p, m))));
            }
            List<Load> loads = new ArrayList<>();
            for (Map.Entry<String, LoadConfig> e : cfg.datasets().entrySet()) {
                List<InputFile> files = byDataset.get(e.getKey());
                if (files == null) {
                    if (!quiet) {
                        out.println("  dataset " + e.getKey() + ": not in the input (its table is left as it is)");
                    }
                    continue;
                }
                files.sort(Comparator.comparing(InputFile::path));
                loads.add(new Load(e.getKey(), e.getValue(), files));
            }
            if (loads.isEmpty()) {
                throw new Archives.ArchiveException("no configured dataset is in the input");
            }
            return loads;
        }
    }

    /** Every dataset's load (each checked first, when asked and there are several), then the report over them; the exit code. */
    static int runAll(List<Load> loads, LoadConfig cfg, PrintStream out, boolean json, boolean quiet, long t0) {
        ObjectNode all = LoadReport.JSON.createObjectNode();
        ObjectNode each = all.putObject("datasets");
        all.put("status", "failed");
        if (cfg.archive().validate().equals("rows") && loads.size() > 1) {
            PrintStream none = new PrintStream(OutputStream.nullOutputStream());
            for (Load l : loads) {
                Result r = LakeLoad.runOne(l.cfg().dryRun(), l.files(), none, false, true, System.nanoTime());
                if (r.code() == LakeLoad.FAILED) {
                    String why = r.summary().path("error").asText("it failed");
                    String msg = "dataset " + l.dataset() + " failed its check (" + why + "): no dataset was loaded";
                    all.put("error", msg);
                    each.set(l.dataset(), r.summary());
                    out.println("lakeload: " + msg);
                    finish(cfg, all, out, json);
                    return LakeLoad.FAILED;
                }
            }
        }
        int code = LakeLoad.OK;
        List<String> lines = new ArrayList<>();
        for (Load l : loads) {
            if (!quiet) {
                out.println("dataset " + l.dataset() + ":");
            }
            Result r = LakeLoad.runOne(l.cfg(), l.files(), out, false, quiet, t0);
            each.set(l.dataset(), r.summary());
            lines.add(line(l, r));
            code = worse(code, r.code());
            if (r.code() == LakeLoad.FAILED || r.code() == LakeLoad.LOCKED) {
                all.put("error", "dataset " + l.dataset() + ": " + r.summary().path("error").asText(r.summary().path("status").asText())
                        + "; the datasets after it were not loaded");
                break;
            }
        }
        all.put("status", code == LakeLoad.OK ? "ok" : code == LakeLoad.PARTIAL ? "partial" : code == LakeLoad.LOCKED ? "locked" : "failed");
        all.put("seconds", Math.round((System.nanoTime() - t0) / 1e7) / 100.0);
        out.println("datasets: " + loads.size());
        lines.forEach(out::println);
        if (all.has("error")) {
            out.println("lakeload: " + all.path("error").asText());
        }
        finish(cfg, all, out, json);
        return code;
    }

    /** One dataset's report line: rows, files, the version its last commit made. */
    private static String line(Load l, Result r) {
        ObjectNode s = r.summary();
        long files = l.files().size();
        long rows = s.path("accepted").asLong();
        List<Long> versions = new ArrayList<>();
        s.path("commit").path("dates").forEach(d -> versions.add(d.path("version").asLong()));
        String v = versions.isEmpty() ? "nothing committed" : versions.size() == 1 ? "version " + versions.get(0)
                : "versions " + versions.stream().min(Long::compare).get() + " to " + versions.stream().max(Long::compare).get();
        return String.format(Locale.ROOT, "  %-20s %,d rows  %,d file%s  %s  (%s)", l.dataset(), rows, files, files == 1 ? "" : "s", v,
                s.path("status").asText());
    }

    private static int worse(int a, int b) {
        int[] rank = {0, 3, 1, 2};                                  // OK < PARTIAL < LOCKED < FAILED
        return rank[b] > rank[a] ? b : a;
    }

    private static void finish(LoadConfig cfg, ObjectNode all, PrintStream out, boolean json) {
        if (cfg.datasetsSummary() != null) {
            try {
                Path f = Path.of(cfg.datasetsSummary());
                if (f.toAbsolutePath().getParent() != null) {
                    Files.createDirectories(f.toAbsolutePath().getParent());
                }
                all.put("summaryFile", f.toAbsolutePath().toString());
                Files.writeString(f, all.toPrettyString());
            } catch (IOException e) {
                out.println("lakeload: the summary could not be written: " + e.getMessage());
            }
        }
        if (json) {
            out.println(all.toPrettyString());
        }
    }

    /** The dataset rows of a summary (for tests): name → accepted rows. */
    static Map<String, Long> rows(JsonNode all) {
        Map<String, Long> out = new LinkedHashMap<>();
        all.path("datasets").fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().path("accepted").asLong()));
        return out;
    }
}
