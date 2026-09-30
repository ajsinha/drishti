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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Sign-in and administration racing each other: lockout holds, admin changes stick, an admin always remains. */
class UserServiceConcurrencyTest {

    @TempDir
    Path dir;

    private UserService service() {
        IdentityProperties p = new IdentityProperties(dir.resolve("users.json").toString(), dir.resolve("audit.jsonl").toString(),
                1000, 10, 3, Duration.ofMinutes(15), true, null, null, null, false, false, dir.resolve("prefs").toString());
        UserService s = new UserService(new FileUserStore(Path.of(p.usersFile())), new PasswordHasher(1000), new AuditLog(Path.of(p.auditFile())), p,
                Set.of("admin", "trader"));
        s.seedIfEmpty();
        return s;
    }

    private static List<Future<?>> race(int threads, Runnable body) throws InterruptedException {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> out = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            out.add(pool.submit(() -> {
                go.await();
                body.run();
                return null;
            }));
        }
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        return out;
    }

    @Test
    void parallelWrongGuessesStillLockTheAccount() throws Exception {
        UserService s = service();
        s.create("drishti-dev-admin", "tina", new UserService.Profile("Tina", "", "FX", Set.of("trader"), true), "trader-pass-1", false);
        race(24, () -> {
            try {
                s.authenticate("tina", "guess");
            } catch (DrishtiException expected) {
                // wrong password or locked
            }
        });
        assertThatThrownBy(() -> s.authenticate("tina", "trader-pass-1"))
                .satisfies(e -> assertThat(((DrishtiException) e).errorCode().code()).isEqualTo("DRS-6005"));
    }

    @Test
    void aSignInFinishingLateNeverUndoesAnAdminDisable() throws Exception {
        UserService s = service();
        s.create("drishti-dev-admin", "tina", new UserService.Profile("Tina", "", "FX", Set.of("trader"), true), "trader-pass-1", false);
        AtomicBoolean stop = new AtomicBoolean();
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> logins = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            logins.add(pool.submit(() -> {
                while (!stop.get()) {
                    try {
                        s.authenticate("tina", "trader-pass-1");
                    } catch (DrishtiException disabled) {
                        // once disabled, refused
                    }
                }
                return null;
            }));
        }
        Thread.sleep(50);
        s.setEnabled("drishti-dev-admin", "tina", false);
        Thread.sleep(100);                    // logins that started before the disable finish meanwhile
        stop.set(true);
        for (Future<?> f : logins) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(s.require("tina").enabled()).isFalse();
    }

    @Test
    void twoAdminsDisablingEachOtherLeaveOneAdmin() throws Exception {
        for (int round = 0; round < 10; round++) {
            UserService s = service();
            s.create("drishti-dev-admin", "ada", new UserService.Profile("Ada", "", "Ops", Set.of("admin"), true), "admin-pass-" + round + "x", false);
            ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            CountDownLatch go = new CountDownLatch(1);
            Future<?> a = pool.submit(() -> {
                go.await();
                tryDisable(s, "ada", "drishti-dev-admin");
                return null;
            });
            Future<?> b = pool.submit(() -> {
                go.await();
                tryDisable(s, "drishti-dev-admin", "ada");
                return null;
            });
            go.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            long admins = s.list("").stream().filter(u -> u.enabled() && u.roles().contains("admin")).count();
            assertThat(admins).as("round " + round).isGreaterThanOrEqualTo(1);
            dir.resolve("users.json").toFile().delete();
        }
    }

    private static void tryDisable(UserService s, String actor, String target) {
        try {
            s.setEnabled(actor, target, false);
        } catch (DrishtiException lastAdmin) {
            // the rule held
        }
    }
}
