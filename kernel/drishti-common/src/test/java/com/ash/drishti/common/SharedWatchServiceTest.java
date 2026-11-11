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
package com.ash.drishti.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SharedWatchServiceTest {

    @TempDir
    Path tmp;

    @Test
    void manyWatchersShareOneServiceAndClosingThemAllReleasesIt() throws Exception {
        SharedWatchService shared = new SharedWatchService(tmp.getFileSystem());
        List<SharedWatchService.Registration> regs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Path d = Files.createDirectory(tmp.resolve("d" + i));
            regs.add(shared.register(d, () -> { }));
        }
        assertThat(shared.servicesOpened()).isEqualTo(1);
        assertThat(shared.open()).isTrue();
        regs.forEach(SharedWatchService.Registration::close);
        assertThat(shared.open()).isFalse();
        SharedWatchService.Registration again = shared.register(tmp, () -> { });
        assertThat(shared.servicesOpened()).isEqualTo(2);       // a new service only after the old one was released
        again.close();
    }

    @Test
    void fortyContextsOfWatchersNeverNeedMoreThanTwoInstances() throws Exception {
        SharedWatchService shared = new SharedWatchService(tmp.getFileSystem());
        Path dir = Files.createDirectory(tmp.resolve("shared"));
        List<SharedWatchService.Registration> regs = new ArrayList<>();
        for (int context = 0; context < 40; context++) {
            for (int w = 0; w < 6; w++) {                       // six watchers per server context
                regs.add(shared.register(dir, () -> { }));
            }
            if (context % 2 == 1) {                             // contexts come and go, as in a test run
                for (int k = 0; k < 6; k++) {
                    regs.remove(0).close();
                }
            }
        }
        assertThat(shared.servicesOpened()).isLessThanOrEqualTo(2);
        regs.forEach(SharedWatchService.Registration::close);
        assertThat(shared.open()).isFalse();
    }

    @Test
    void listenersHearChangesAndOnlyWhileRegistered() throws Exception {
        SharedWatchService shared = new SharedWatchService(tmp.getFileSystem());
        Path dir = Files.createDirectory(tmp.resolve("watched"));
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        CountDownLatch heard = new CountDownLatch(1);
        SharedWatchService.Registration ra = shared.register(dir, () -> {
            a.incrementAndGet();
            heard.countDown();
        });
        SharedWatchService.Registration rb = shared.register(dir, b::incrementAndGet);
        rb.close();
        Files.writeString(dir.resolve("x.txt"), "1");
        assertThat(heard.await(20, TimeUnit.SECONDS)).isTrue();
        assertThat(a.get()).isPositive();
        assertThat(b.get()).isZero();
        ra.close();
    }

    @Test
    void aDeletedFolderInvalidatesItsRegistrationAndCanBeRegisteredAgain() throws Exception {
        SharedWatchService shared = new SharedWatchService(tmp.getFileSystem());
        Path dir = Files.createDirectory(tmp.resolve("gone"));
        CountDownLatch heard = new CountDownLatch(1);
        SharedWatchService.Registration r = shared.register(dir, heard::countDown);
        Files.delete(dir);
        assertThat(heard.await(20, TimeUnit.SECONDS)).isTrue();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (r.valid() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        assertThat(r.valid()).isFalse();
        r.close();
        Files.createDirectory(dir);
        SharedWatchService.Registration back = shared.register(dir, () -> { });
        assertThat(back.valid()).isTrue();
        back.close();
        assertThat(shared.open()).isFalse();
    }

    @Test
    void aMissingFolderFailsWithoutLeavingAServiceOpen() {
        SharedWatchService shared = new SharedWatchService(tmp.getFileSystem());
        try {
            shared.register(tmp.resolve("nope"), () -> { });
        } catch (IOException expected) {
            assertThat(shared.open()).isFalse();
            return;
        }
        throw new AssertionError("registering a missing folder should fail");
    }
}
