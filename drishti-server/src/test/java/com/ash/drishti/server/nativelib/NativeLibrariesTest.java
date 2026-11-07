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
package com.ash.drishti.server.nativelib;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.nativ.linalg.Blas;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The server's BLAS binding as Admin, Health, the meters and the digest show it: whichever path this JVM bound (Java or the bundled
 * OpenBLAS), the gauges say it, health is OK or DEGRADED (never down) and a fallback carries its banner; the digest's word names the path.
 */
class NativeLibrariesTest {

    @Test
    void theMetersHealthAndDigestSayWhatIsBound() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("t", Map.of("drishti.native.blas.mode", "auto", "drishti.native.blas.canary-interval", "0s")));
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        DefaultListableBeanFactory f = new DefaultListableBeanFactory();
        f.registerSingleton("registry", reg);
        NativeLibraries n = new NativeLibraries(env, f.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        Blas.Status s = Blas.status();
        Gauge path = reg.find("drishti.native.blas").tag("path", s.isNative() ? "native" : "java").gauge();
        assertThat(path).isNotNull();
        assertThat(path.value()).isEqualTo(1.0);
        assertThat(reg.find("drishti.native.blas.fallback").gauge().value()).isEqualTo(s.degraded() ? 1.0 : 0.0);
        Map<String, Object> d = n.describe();
        assertThat(d.get("status")).isIn("OK", "DEGRADED");
        assertThat(d.get("status")).isEqualTo(s.degraded() ? "DEGRADED" : "OK");
        assertThat((List<?>) d.get("banner")).hasSize(s.degraded() ? 8 : 0);
        assertThat(d).containsKeys("summary", "line", "members", "canaryInterval");
        assertThat(n.degraded()).isEqualTo(s.degraded());
    }

    @Test
    void theDigestWordAndTheReasonTag() {
        Blas.Status nat = new Blas.Status(Blas.Mode.AUTO, "openblas", "/data/native-cache/libjniopenblas_nolapack.so", "0.3.26", 1, null);
        Blas.Status fell = new Blas.Status(Blas.Mode.AUTO, "java", null, null, 0, "no bundled OpenBLAS for macosx-aarch64\n(bundled: ...)");
        Blas.Status chose = new Blas.Status(Blas.Mode.JAVA, "java", null, null, 0, "drishti.native.blas.mode is java");
        assertThat(NativeLibraries.wire(nat)).isEqualTo("native 0.3.26");
        assertThat(NativeLibraries.wire(fell)).isEqualTo("java-fallback no bundled OpenBLAS for macosx-aarch64 (bundled: ...)");
        assertThat(NativeLibraries.wire(chose)).startsWith("java drishti.native.blas.mode is java");
        assertThat(NativeLibraries.reasonTag(nat)).isEqualTo("none");
        assertThat(NativeLibraries.reasonTag(new Blas.Status(Blas.Mode.AUTO, "java", null, null, 0, "x".repeat(300)))).hasSize(120).endsWith("...");
        assertThat(nat.summary()).isEqualTo("native (bundled OpenBLAS 0.3.26)");
        assertThat(fell.health()).isEqualTo("DEGRADED");
        assertThat(chose.health()).isEqualTo("OK");
    }

    @Test
    void theClusterAgreesOnJavaWhenAnyMemberIsOnJava() {
        try {
            String r = NativeLibraries.agree(Map.of("node-b", "native 0.3.26", "node-c", "java-fallback no bundled OpenBLAS for linux-aarch64"));
            assertThat(r).contains("node-c (java-fallback no bundled OpenBLAS for linux-aarch64)").contains("every member sums on Java");
            assertThat(Blas.clusterJava()).isEqualTo(r);
            assertThat(Blas.forClusterSums()).isSameAs(com.ash.drishti.nativ.linalg.JavaBlas.INSTANCE);
            assertThat(NativeLibraries.agree(Map.of("node-d", "unknown (an older server: Java)"))).contains("node-d");
            assertThat(NativeLibraries.agree(Map.of("node-b", "native 0.3.26"))).isNull();   // every member native: the agreement lifts
            assertThat(Blas.clusterJava()).isNull();
            assertThat(Blas.forClusterSums()).isSameAs(Blas.get());
            assertThat(NativeLibraries.agree(Map.of())).isNull();                             // alone: its own binding
        } finally {
            Blas.clusterOnJava(null);
        }
    }

    @Test
    void theEngineSwitchChoosesJavaLiveAndOnRunsTheSelfTestOrRefusesInWords() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("t", Map.of("drishti.native.blas.canary-interval", "0s")));
        NativeLibraries n = new NativeLibraries(env, new DefaultListableBeanFactory().getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
        OpenBlasEngine e = new OpenBlasEngine(n);
        assertThat(e.id()).isEqualTo("openblas");
        e.changing("alice", "2026-11-04T00:10:00Z", "benchmark on Java");
        e.stop();
        Blas.Status s = Blas.status();
        assertThat(s.summary()).isEqualTo("java (chosen by admin: switched off by alice at 2026-11-04T00:10:00Z (benchmark on Java))");
        assertThat(s.degraded()).isFalse();
        assertThat(n.describe().get("status")).isEqualTo("OK");                         // a notice, never DEGRADED
        assertThat(NativeLibraries.wire(s)).startsWith("java chosen by admin");           // the members count it as Java
        assertThat(Blas.get()).isSameAs(com.ash.drishti.nativ.linalg.JavaBlas.INSTANCE);
        try {
            e.start();                                                                    // the self-test, then OpenBLAS
            assertThat(Blas.status().isNative()).isTrue();
            assertThat(Blas.adminJava()).isNull();
        } catch (IllegalStateException refused) {                                         // no OpenBLAS here: refused in words
            assertThat(refused.getMessage()).contains("the pure-Java path stays");
            assertThat(Blas.status().summary()).startsWith("java (chosen by admin");
        }
    }
}
