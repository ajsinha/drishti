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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.net.ServerSocket;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DATA-03: a connector without {@code kinds} that has not reached MongoDB cannot tell which kinds it holds, so a read
 * fails (DRS-1003) rather than saying "not held" and letting another store answer with other data. No database needed:
 * the port is closed.
 */
class MongoNotReachedTest {

    @Test
    void aReadBeforeMongoDbHasAnsweredFailsInsteadOfPassing() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        MongoSourcePlugin p = new MongoSourcePlugin();
        p.start(DatedSourceContract.context(Map.of("uri", "mongodb://localhost:" + port, "connect-timeout-ms", "300", "source-name", "desk-mongo",
                "refresh-seconds", "3600")));
        try {
            assertThatThrownBy(() -> p.fetch(EntityRef.of("trade", "T-1"))).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("desk-mongo has not reached MongoDB yet");
        } finally {
            p.close();
        }
    }
}
