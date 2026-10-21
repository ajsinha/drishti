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
package com.ash.drishti.identity.design;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.content.ContentStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one-time import of Designs from the old stores into the content store (docs/architecture/CONTENT_STORE.md section 5). Every item is put
 * with "must be absent", so what is already in the store is never overwritten and a rerun is harmless. The file store's folder is renamed
 * {@code designs-imported-<time>} once every Design is in (left in place on a failure, for the next start); the database rows are left
 * in place and a marker item records that they were imported, so a Design deleted later does not come back.
 */
public final class DesignImport {

    private static final Logger LOG = LoggerFactory.getLogger(DesignImport.class);
    static final String JPA_MARKER = "system/imports/designs-jpa.json";

    /** What one import did. */
    public record Result(int imported, int skipped, int failed) {}

    private DesignImport() {}

    /** Imports {@code dir} (the old file store) into {@code to}; renames the folder when nothing failed. Nothing to do when the folder is absent. */
    public static Result fromFiles(Path dir, ContentDesignStore to) {
        if (dir == null || !Files.isDirectory(dir)) {
            return new Result(0, 0, 0);
        }
        Result r = copy(new FileDesignStore(dir), to, "files " + dir);
        if (r.failed() == 0) {
            Path renamed = dir.resolveSibling(dir.getFileName() + "-imported-" + LocalDateTime.now(java.time.ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            try {
                Files.move(dir, renamed);
                LOG.info("designs: the old folder {} is kept as {} (safe to delete once you are happy)", dir, renamed);
            } catch (IOException e) {
                LOG.warn("designs: imported, but the old folder {} could not be renamed ({}); the next start imports nothing new from it", dir, e.toString());
            }
        }
        return r;
    }

    /** Imports the identity database's Designs ({@code store: jpa}) once; a marker item keeps later starts from importing again. */
    public static Result fromDatabase(DesignStore jpa, ContentDesignStore to) {
        ContentStore c = to.content();
        if (c.get(JPA_MARKER).isPresent()) {
            return new Result(0, 0, 0);
        }
        Result r = copy(jpa, to, "the identity database");
        if (r.failed() == 0) {
            String marker = "{\"imported\":" + r.imported() + ",\"skipped\":" + r.skipped() + ",\"at\":" + System.currentTimeMillis() + "}";
            try {
                c.put(JPA_MARKER, marker.getBytes(StandardCharsets.UTF_8), null);
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT) {
                    throw e;                                             // another node wrote it first: the same import
                }
            }
        }
        return r;
    }

    static Result copy(DesignStore from, ContentDesignStore to, String what) {
        int imported = 0;
        int skipped = 0;
        int failed = 0;
        ContentStore c = to.content();
        for (String user : from.users()) {
            for (StoredDesign d : from.list(user)) {
                try {
                    if (c.get(ContentDesignStore.designKey(user, d.id)).isPresent()) {
                        skipped++;
                        continue;
                    }
                    for (StoredDesign.SampleInfo s : d.samples) {
                        if (!StoredDesign.REF.equals(s.type())) {
                            from.sample(user, d.id, s.name()).ifPresent(text -> to.putSample(user, d.id, s.name(), text));
                        }
                    }
                    StoredDesign copy = d.copy();
                    copy.storeVersion = null;
                    to.save(copy);                                       // last, so a Design shows only once its samples are in
                    imported++;
                } catch (DrishtiException e) {
                    if (e.errorCode() == ErrorCode.CONTENT_CONFLICT) {
                        skipped++;                                       // another node imported it at the same moment
                    } else {
                        failed++;
                        LOG.warn("designs: could not import {} of {} from {}: {}", d.id, user, what, e.getMessage());
                    }
                } catch (RuntimeException e) {
                    failed++;
                    LOG.warn("designs: could not import {} of {} from {}: {}", d.id, user, what, e.toString());
                }
            }
        }
        if (imported + skipped + failed > 0) {
            LOG.info("designs: imported {} from {} into the content store ({} already there, {} failed)", imported, what, skipped, failed);
        }
        return new Result(imported, skipped, failed);
    }
}
