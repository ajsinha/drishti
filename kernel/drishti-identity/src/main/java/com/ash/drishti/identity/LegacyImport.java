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
package com.ash.drishti.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Iterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves the files of releases before 1.10 (users.json, audit.jsonl, preferences/*.json) into the identity database,
 * once: only when the database holds no users. Each file is then renamed {@code *.imported}, never deleted.
 */
final class LegacyImport {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyImport.class);

    private LegacyImport() {}

    static void run(IdentityProperties props, JpaUserStore users, JpaAuditLog audit, PreferenceStore prefs) {
        Path usersFile = Path.of(props.usersFile());
        if (!users.isEmpty() || !Files.isRegularFile(usersFile)) {
            return;
        }
        int u = 0;
        for (User user : new FileUserStore(usersFile).all()) {
            users.put(user);
            u++;
        }
        int a = importAudit(Path.of(props.auditFile()), audit);
        int p = importPreferences(Path.of(props.preferencesDir()), prefs);
        retire(usersFile);
        retire(Path.of(props.auditFile()));
        audit.record("system", "identity-imported", "", u + " users, " + a + " audit events, " + p + " saved documents from " + usersFile.getParent());
        LOG.info("imported {} users, {} audit events and {} saved documents into the identity database", u, a, p);
    }

    private static int importAudit(Path file, JpaAuditLog audit) {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
        int n = 0;
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            for (String line; (line = in.readLine()) != null; ) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode e = json.readTree(line);
                audit.recordAt(new AuditLog.Event(Instant.parse(e.path("at").asText()), e.path("actor").asText(""), e.path("action").asText(""),
                        e.path("subject").asText(""), e.path("detail").asText("")));
                n++;
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("audit import stopped after {} events: {}", n, e.getMessage());
        }
        return n;
    }

    private static int importPreferences(Path dir, PreferenceStore prefs) {
        FilePreferenceStore files = new FilePreferenceStore(dir, Integer.MAX_VALUE, Integer.MAX_VALUE);
        int n = 0;
        for (String user : files.users()) {
            JsonNode all = files.document(user);
            for (Iterator<String> nss = all.fieldNames(); nss.hasNext(); ) {
                String ns = nss.next();
                for (Iterator<String> keys = all.path(ns).fieldNames(); keys.hasNext(); ) {
                    String key = keys.next();
                    try {
                        prefs.put(user, ns, key, all.path(ns).path(key));
                        n++;
                    } catch (RuntimeException e) {
                        LOG.warn("skipped saved document {}/{}/{}: {}", user, ns, key, e.getMessage());
                    }
                }
            }
        }
        if (Files.isDirectory(dir)) {
            retire(dir);
        }
        return n;
    }

    private static void retire(Path p) {
        try {
            if (Files.exists(p)) {
                Files.move(p, p.resolveSibling(p.getFileName() + ".imported"), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warn("could not rename {} after importing it: {}", p, e.getMessage());
        }
    }
}
