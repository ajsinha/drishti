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

import java.util.Map;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The identity database on PostgreSQL 18 (Docker; skipped where Docker is not reachable). */
@Testcontainers(disabledWithoutDocker = true)
class PostgresIdentityStoreTest extends IdentityStoreContract {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Override
    protected Map<String, Object> database() {
        return Map.of("drishti.identity.database-url", POSTGRES.getJdbcUrl(), "drishti.identity.database-user", POSTGRES.getUsername(),
                "drishti.identity.database-password", POSTGRES.getPassword());
    }
}
