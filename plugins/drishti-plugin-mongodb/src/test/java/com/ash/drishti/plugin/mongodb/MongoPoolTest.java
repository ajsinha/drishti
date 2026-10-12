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
package com.ash.drishti.plugin.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.pool.PoolSettings;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** The Mongo driver's pool is sized by pool-size (default 20), and every operation gives its connection back. */
class MongoPoolTest {

    private static MongoClientSettings.Builder builder(String uri, MongoPool pool) {
        MongoClientSettings.Builder b = MongoClientSettings.builder().applyConnectionString(new ConnectionString(uri));
        pool.apply(b);
        return b;
    }

    @Test
    void thePoolIsTwentyByDefaultAndTheConnectorsOwnSizeOverridesIt() {
        MongoClientSettings dflt = builder("mongodb://localhost:27017", new MongoPool("m", PoolSettings.resolve(Map.of()))).build();
        assertThat(dflt.getConnectionPoolSettings().getMaxSize()).isEqualTo(20);
        assertThat(dflt.getConnectionPoolSettings().getMinSize()).isEqualTo(2);
        assertThat(dflt.getConnectionPoolSettings().getMaxWaitTime(TimeUnit.SECONDS)).isEqualTo(10);
        assertThat(dflt.getConnectionPoolSettings().getMaxConnectionLifeTime(TimeUnit.MINUTES)).isEqualTo(30);
        assertThat(dflt.getSocketSettings().getReadTimeout(TimeUnit.MINUTES)).isEqualTo(5);
        MongoClientSettings own = builder("mongodb://localhost:27017", new MongoPool("m", PoolSettings.resolve(Map.of("pool-size", "7", "pool-min-idle", "1",
                "pool-connection-timeout", "2s", "statement-timeout", "30s")))).build();
        assertThat(own.getConnectionPoolSettings().getMaxSize()).isEqualTo(7);
        assertThat(own.getConnectionPoolSettings().getMinSize()).isEqualTo(1);
        assertThat(own.getConnectionPoolSettings().getMaxWaitTime(TimeUnit.SECONDS)).isEqualTo(2);
        assertThat(own.getSocketSettings().getReadTimeout(TimeUnit.SECONDS)).isEqualTo(30);
    }

    @Test
    @SuppressWarnings("resource")
    void operationsReturnTheirConnection() throws Exception {
        try (GenericContainer<?> mongo = new GenericContainer<>("mongo:7").withExposedPorts(27017)
                .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1).withStartupTimeout(Duration.ofMinutes(2)))) {
            mongo.start();
            MongoPool pool = new MongoPool("m", PoolSettings.resolve(Map.of("pool-size", "4", "pool-min-idle", "0", "pool-leak-threshold", "60s")));
            try (MongoClient client = MongoClients.create(builder("mongodb://" + mongo.getHost() + ":" + mongo.getMappedPort(27017), pool).build())) {
                MongoCollection<Document> c = client.getDatabase("pooltest").getCollection("t");
                for (int i = 0; i < 10; i++) {
                    c.insertOne(new Document("n", i));
                }
                for (int i = 0; i < 50; i++) {
                    assertThat(c.countDocuments()).isEqualTo(10);
                }
                assertThat(pool.stats().inUse()).isZero();
                assertThat(pool.stats().max()).isEqualTo(4);
                assertThat(pool.stats().open()).isBetween(1, 4);
                assertThat(pool.stats().inUse()).isZero();
            }
        }
    }
}
