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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/** The identity database on SQLite (the default). */
class SqliteIdentityStoreTest extends IdentityStoreContract {

    @Override
    protected Map<String, Object> database() {
        return Map.of("drishti.identity.database-url", "jdbc:sqlite:" + tmp.resolve("identity/drishti.db"));
    }

    /**
     * Two identity databases in one JVM (the nodes of an in-JVM cluster) read at the same moment: each read gets its own rows' times.
     * The instants used to be read through one calendar shared by every thread of the JVM (Hibernate's TIMESTAMP_UTC passes a static
     * calendar and the SQLite driver sets the row's time on it), so two reads at once could swap their times: a node read a peer's newer
     * seeded administrator in place of its own (NetLastAdminTest). One database's transactions take turns, so it takes two.
     */
    @Test
    void twoDatabasesReadAtTheSameMomentEachGetTheirOwnTimes() throws Exception {
        ConfigurableApplicationContext other = open(Map.of("drishti.identity.database-url", "jdbc:sqlite:" + tmp.resolve("identity-2/drishti.db")));
        JpaUserStore one = bean(JpaUserStore.class);
        JpaUserStore two = other.getBean(JpaUserStore.class);
        Instant base = Instant.parse("2026-11-05T10:00:00.123Z");
        long year = 86_400L * 365;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Instant a = base.plusSeconds(3_600L * i);
            Instant b = a.plusSeconds(year);                                // the other database's rows: a year later
            one.put(new User("u-times-" + i, "T" + i, "", "", Set.of("trader"), true, false, "h", 0, null, a, a, null, null, null));
            two.put(new User("u-times-" + i, "T" + i, "", "", Set.of("trader"), true, false, "h", 0, null, b, b, null, null, null));
            names.add("u-times-" + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<List<String>>> runs = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                JpaUserStore store = t % 2 == 0 ? one : two;
                long shift = t % 2 == 0 ? 0 : year;
                runs.add(pool.submit(() -> {
                    List<String> wrong = new ArrayList<>();
                    for (int n = 0; n < 200; n++) {
                        for (User u : store.all()) {
                            if (u.username().startsWith("u-times-")) {
                                Instant want = base.plusSeconds(3_600L * Integer.parseInt(u.username().substring(8)) + shift);
                                if (!want.equals(u.createdAt()) || !want.equals(u.updatedAt())) {
                                    wrong.add(u.username() + " " + u.createdAt() + "/" + u.updatedAt() + " not " + want);
                                }
                            }
                        }
                    }
                    return wrong;
                }));
            }
            List<String> wrong = new ArrayList<>();
            for (Future<List<String>> f : runs) {
                wrong.addAll(f.get());
            }
            assertThat(wrong).as("rows read with the other database's times").isEmpty();
        } finally {
            pool.shutdownNow();
            names.forEach(one::delete);
            other.close();
        }
    }
}
