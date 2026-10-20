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
package com.ash.drishti.plugin.cassandra;

import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The same connector, loader and tests against ScyllaDB in Docker (one shard, developer mode): the contract, the promoted
 * columns, documents, the catalogue and the pool. Skipped where Docker is not reachable.
 */
@Testcontainers(disabledWithoutDocker = true)
class ScyllaSourcePluginTest extends CassandraSourcePluginTest {

    @Override
    protected String node() {
        return CassandraNode.scylla();
    }

    @Override
    protected String keyspace() {
        return "scylla_desk";
    }
}
