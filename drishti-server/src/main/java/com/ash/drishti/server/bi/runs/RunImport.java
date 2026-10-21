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
package com.ash.drishti.server.bi.runs;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.bi.BiDocs;
import com.ash.drishti.identity.content.ContentStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one-time import of BI runs into the content store (docs/architecture/CONTENT_STORE.md section 9): the {@code drishti_bi_run},
 * {@code drishti_bi_run_share} and {@code drishti_bi_run_schedule} rows, and the results and promoted snapshots under {@code store.dir}
 * ({@code <data>/bi-runs}). Every document is created with "must be absent", so a rerun is harmless; a marker
 * ({@code system/imports/bi-runs.json}) keeps later starts from importing again, so a run deleted later does not come back. The rows and files
 * are left in place.
 */
public final class RunImport {

    private static final Logger LOG = LoggerFactory.getLogger(RunImport.class);
    static final String MARKER = "system/imports/bi-runs.json";

    /** What the import did: documents created, skipped (already there) and failed. */
    public record Result(int imported, int skipped, int failed) {}

    private RunImport() {}

    /** Imports the rows ({@code records}, {@code shares}, {@code schedules}; any may be null) and the files under {@code dir}, once. */
    public static Result run(ContentStore to, RunRecords records, RunShares shares, RunSchedules.Store schedules, Path dir) {
        if (to.get(MARKER).isPresent()) {
            return new Result(0, 0, 0);
        }
        int[] n = new int[3];
        try {
            if (records != null) {
                for (Run r : records.inStates(Arrays.asList(RunState.values()), Integer.MAX_VALUE)) {
                    put(to, ContentRunRecords.key(r.id()), BiDocs.bytes(r), n);
                    if (shares != null) {
                        for (RunShares.Share s : shares.of(r.id())) {
                            put(to, ContentRunRecords.Shares.key(s.runId(), s.recipient()), BiDocs.bytes(s), n);
                        }
                    }
                }
            }
            if (schedules != null) {
                for (RunSchedules.Schedule s : schedules.all()) {
                    put(to, ContentRunRecords.Schedules.key(s.id()), BiDocs.bytes(s), n);
                }
            }
            files(to, dir, n);
        } catch (RuntimeException e) {
            n[2]++;
            LOG.warn("BI runs: the import into the content store stopped: {}", e.getMessage());
        }
        Result r = new Result(n[0], n[1], n[2]);
        if (r.failed() == 0) {
            String marker = "{\"imported\":" + r.imported() + ",\"skipped\":" + r.skipped() + ",\"at\":" + System.currentTimeMillis() + "}";
            try {
                to.put(MARKER, marker.getBytes(StandardCharsets.UTF_8), null);
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT) {
                    throw e;                                             // another node wrote it first: the same import
                }
            }
        }
        if (r.imported() + r.failed() > 0) {
            LOG.info("BI runs: imported {} document(s) into the content store ({} already there, {} failed)", r.imported(), r.skipped(), r.failed());
        }
        return r;
    }

    private static void put(ContentStore to, String key, byte[] bytes, int[] n) {
        try {
            to.put(key, bytes, null);
            n[0]++;
        } catch (DrishtiException e) {
            if (e.errorCode() != ErrorCode.CONTENT_CONFLICT) {
                n[2]++;
                LOG.warn("BI runs import: {} failed: {}", key, e.getMessage());
            } else {
                n[1]++;
            }
        }
    }

    private static void files(ContentStore to, Path dir, int[] n) {
        if (dir == null) {
            return;
        }
        Path results = dir.resolve("results");
        if (Files.isDirectory(results)) {
            try (Stream<Path> s = Files.walk(results, 3)) {
                for (Path answer : s.filter(p -> p.getFileName().toString().equals("answer.json")).toList()) {
                    String key = answer.getParent().getFileName().toString();
                    if (!key.matches("[0-9a-f]{16,64}")) {
                        continue;
                    }
                    byte[] bytes = Files.readAllBytes(answer);
                    Path who = answer.resolveSibling(RunStore.OWNER);
                    String owner = Files.isRegularFile(who) ? Files.readString(who, StandardCharsets.UTF_8).strip() : "";
                    put(to, ContentRunStore.RESULTS + key + "/answer.json", bytes, n);
                    put(to, ContentRunStore.RESULTS + key + "/meta.json",
                            BiDocs.bytes(new ContentRunStore.Meta(owner, bytes.length, Files.getLastModifiedTime(answer).toInstant())), n);
                }
            } catch (IOException e) {
                n[2]++;
                LOG.warn("BI runs import: the results under {} could not be read: {}", results, e.getMessage());
            }
        }
        Path snapshots = dir.resolve("snapshots");
        if (Files.isDirectory(snapshots)) {
            try (Stream<Path> s = Files.walk(snapshots, 3)) {
                for (Path answer : s.filter(p -> p.getFileName().toString().equals("answer.json")).toList()) {
                    String v = answer.getParent().getFileName().toString();
                    String name = answer.getParent().getParent().getFileName().toString();
                    if (!v.matches("v[1-9][0-9]{0,8}") || !name.matches("[a-z0-9][a-z0-9._-]{0,127}")) {
                        continue;
                    }
                    put(to, ContentRunStore.SNAPSHOTS + BiDocs.seg(name) + "/" + BiDocs.num(Integer.parseInt(v.substring(1))) + ".json",
                            Files.readAllBytes(answer), n);
                }
            } catch (IOException e) {
                n[2]++;
                LOG.warn("BI runs import: the snapshots under {} could not be read: {}", snapshots, e.getMessage());
            }
        }
    }
}
