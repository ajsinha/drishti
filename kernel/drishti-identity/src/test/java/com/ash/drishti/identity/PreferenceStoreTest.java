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

import com.ash.drishti.api.error.DrishtiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreferenceStoreTest {

    @TempDir
    Path dir;
    final ObjectMapper json = new ObjectMapper();

    @Test
    void storesPerUserAndNamespaceWithLimits() throws Exception {
        PreferenceStore s = new FilePreferenceStore(dir, 200, 2);
        s.put("ash", "workspaces", "Credit desk", json.readTree("{\"layout\":\"2col\"}"));
        s.put("ash", "workspaces", "Rates", json.readTree("{\"layout\":\"2x2\"}"));
        s.put("tina", "workspaces", "Mine", json.readTree("{}"));
        assertThat(s.keys("ash", "workspaces")).containsExactly("Credit desk", "Rates");
        assertThat(s.get("ash", "workspaces", "Rates").orElseThrow().get("layout").asText()).isEqualTo("2x2");
        assertThat(s.keys("tina", "workspaces")).containsExactly("Mine");
        assertThatThrownBy(() -> s.put("ash", "workspaces", "Third", json.readTree("{}"))).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> s.put("ash", "workspaces", "../evil", json.readTree("{}"))).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> s.put("ash", "workspaces", "Rates", json.readTree("{\"x\":\"" + "y".repeat(300) + "\"}")))
                .hasMessageContaining("larger");
        assertThat(s.delete("ash", "workspaces", "Rates")).isTrue();
        assertThat(new FilePreferenceStore(dir, 200, 2).keys("ash", "workspaces")).containsExactly("Credit desk");
        s.forget("tina");
        assertThat(s.keys("tina", "workspaces")).isEmpty();
    }

    /**
     * A write replaces the user's file while others read it: never refused. On Windows a file opened by java.io (Jackson's read of a
     * File) shares no delete, so the replace was refused (AccessDeniedException) while a reader had it open; reads go through NIO now.
     */
    @Test
    void aWriteReplacesTheFileWhileOthersReadIt() throws Exception {
        PreferenceStore s = new FilePreferenceStore(dir, 100_000, 100);
        s.put("ash", "alerts", "a", json.readTree("{\"n\":0}"));
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> failed = new java.util.concurrent.atomic.AtomicReference<>();
        List<Thread> readers = new java.util.ArrayList<>();
        for (int r = 0; r < 4; r++) {
            readers.add(Thread.ofPlatform().start(() -> {
                while (!stop.get()) {
                    try {
                        s.get("ash", "alerts", "a");
                    } catch (Throwable t) {
                        failed.compareAndSet(null, t);
                    }
                }
            }));
        }
        try {
            for (int i = 1; i <= 300; i++) {
                s.put("ash", "alerts", "a", json.readTree("{\"n\":" + i + "}"));
            }
        } finally {
            stop.set(true);
            for (Thread t : readers) {
                t.join();
            }
        }
        assertThat(failed.get()).isNull();
        assertThat(s.get("ash", "alerts", "a").orElseThrow().path("n").asInt()).isEqualTo(300);
    }
}
