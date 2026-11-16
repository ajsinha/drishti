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
package com.ash.drishti.diskcache;
import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link SortedStore#inUse}: a store another process has open is in use (a server's start-up clean-up must leave it), a closed one is not. */
class SortedStoreInUseTest {

    @Test
    void aStoreAnotherProcessHasOpenIsInUseAndFreeOnceItCloses(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("live-1");
        assertThat(SortedStore.inUse(store)).as("no folder").isFalse();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child = new ProcessBuilder(java, "-Djava.io.tmpdir=" + dir, "-cp", System.getProperty("java.class.path"),
                Holder.class.getName(), store.toString()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            String ready = new String(child.getInputStream().readNBytes(5));
            assertThat(ready).isEqualTo("ready");
            assertThat(SortedStore.inUse(store)).as("open in the child").isTrue();
        } finally {
            child.destroy();
            child.waitFor(30, TimeUnit.SECONDS);
        }
        assertThat(Files.isDirectory(store)).isTrue();
        assertThat(SortedStore.inUse(store)).as("the child has gone").isFalse();
    }

    /** Opens a store in the folder it is given, says "ready" and waits to be stopped. */
    public static final class Holder {
        public static void main(String[] args) throws Exception {
            SortedStore s = SortedStore.create(Path.of(args[0]));
            s.put("k", new byte[] {1});
            s.finish();
            System.out.print("ready");
            System.out.flush();
            Thread.sleep(120_000);
        }
    }
}
