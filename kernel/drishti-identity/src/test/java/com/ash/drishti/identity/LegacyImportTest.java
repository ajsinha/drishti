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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Users, audit and saved documents of releases before 1.10 move into the database once, and the files are kept. */
class LegacyImportTest {

    @TempDir
    Path dir;

    private AnnotationConfigApplicationContext open() {
        AnnotationConfigApplicationContext c = new AnnotationConfigApplicationContext();
        c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                "drishti.identity.database-url", "jdbc:sqlite:" + dir.resolve("drishti.db"),
                "drishti.identity.users-file", dir.resolve("users.json").toString(),
                "drishti.identity.audit-file", dir.resolve("audit.jsonl").toString(),
                "drishti.identity.preferences-dir", dir.resolve("preferences").toString(),
                "drishti.identity.iterations", "1000")));
        c.registerBean(RoleNames.class, () -> RoleNames.of(Set.of("admin", "trader")));
        c.register(IdentityConfiguration.class);
        c.refresh();
        return c;
    }

    @Test
    void importsOnceAndRenamesTheFiles() throws Exception {
        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        new FileUserStore(dir.resolve("users.json")).put(new User("tina", "Tina", "t@x.co", "Rates", Set.of("trader"), true, false, "hash", 0,
                null, now, now, null, null, Set.of("finance")));
        new FileAuditLog(dir.resolve("audit.jsonl")).record("ops", "user-created", "tina", "");
        new FilePreferenceStore(dir.resolve("preferences"), 10_000, 10).put("tina", "workspaces", "Rates",
                new ObjectMapper().readTree("{\"layout\":\"2x2\"}"));

        try (AnnotationConfigApplicationContext c = open()) {
            User tina = c.getBean(JpaUserStore.class).find("tina").orElseThrow();
            assertThat(tina.packs()).containsExactly("finance");
            assertThat(tina.passwordHash()).isEqualTo("hash");
            assertThat(c.getBean(JpaUserStore.class).find("drishti-dev-admin")).as("no seed: there were users").isEmpty();
            assertThat(c.getBean(PreferenceStore.class).get("tina", "workspaces", "Rates").orElseThrow().path("layout").asText()).isEqualTo("2x2");
            assertThat(c.getBean(AuditLog.class).recent(10, "tina")).extracting(AuditLog.Event::action).contains("user-created");
        }
        assertThat(dir.resolve("users.json")).doesNotExist();
        assertThat(dir.resolve("users.json.imported")).exists();
        assertThat(dir.resolve("preferences.imported")).isDirectory();

        Files.writeString(dir.resolve("users.json"), "[]");          // a stray file later is never imported again
        try (AnnotationConfigApplicationContext c = open()) {
            assertThat(c.getBean(JpaUserStore.class).all()).extracting(User::username).containsExactly("tina");
        }
    }
}
