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
package com.ash.drishti.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ash.drishti.identity.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A server started under the production profile with safe settings seeds nobody: no development administrator, no demo users, no
 * default-password warning (docs/admin/PRODUCTION_STANDARD.md, "Identity and access").
 */
@SpringBootTest(properties = {"drishti.profile=production", "drishti.production.tls=proxy",
        "drishti.security.secret=test-secret-that-is-at-least-32-bytes-long", "drishti.identity.iterations=1000",
        "drishti.engine-lib.trust=pinned", "drishti.engine-lib.licence=${drishti.test.dir}/prodprofile-licence.yaml",
        "drishti.access.dir=${drishti.test.dir}/prodprofile-access",
        "drishti.identity.database-url=jdbc:sqlite:${drishti.test.dir}/prodprofile-${random.uuid}/identity.db"})
class ProductionProfileStartTest {

    @org.junit.jupiter.api.BeforeAll
    static void licences() throws java.io.IOException {
        java.nio.file.Path root = TestRun.root();
        java.nio.file.Files.writeString(root.resolve("prodprofile-licence.yaml"), "licence:\n  products: [var]\n");
        java.nio.file.Files.createDirectories(root.resolve("prodprofile-access"));
        java.nio.file.Files.writeString(root.resolve("prodprofile-access/licence.yaml"), "licence: {}\n");
    }

    @Autowired UserService users;

    @Test
    void startsSecureAndSeedsNobody() {
        assertEquals(0, users.list("").size());
        assertTrue(users.defaultPasswordUsers().isEmpty());
    }
}
