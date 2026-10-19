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
package com.ash.drishti.engine.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.Provenance;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.common.JsonCodec;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/** The router's fallback (Drishti*net's data forwarding, DRISHTI_NET.md section 7): peers after the local sources, never in their place. */
class SourceRouterFallbackTest {

    /** A source that holds some ids, or fails every read. */
    record Src(String name, Set<String> ids, boolean fails, List<String> asked) implements SourcePlugin {
        public PluginManifest manifest() {
            return new PluginManifest(name, "t", Set.of("trade"), new SourceCapabilities(false, false, false));
        }

        public void start(SourceContext c) {}

        public Optional<EntityDocument> fetch(EntityRef ref) throws IOException {
            asked.add(name);
            if (fails) {
                throw new IOException(name + " is down");
            }
            return ids.contains(ref.id())
                    ? Optional.of(new EntityDocument(ref, DataNode.of(Map.of("from", name)), new Provenance(name, 1, Instant.now(), false)))
                    : Optional.empty();
        }
    }

    private final List<String> asked = new ArrayList<>();

    private SourceRouter router(SourcePlugin... local) {
        var props = new SourcesProperties(Map.of(), null, Map.of(), Duration.ofSeconds(2), null, Map.of());
        return new SourceRouter(new SourceRegistry(List.of(local), props, new JsonCodec()), props, Executors.newVirtualThreadPerTaskExecutor());
    }

    private static String from(SourceRouter r, String id) {
        return r.fetch(EntityRef.of("trade", id), AsOf.LATEST).join().data().get("from").asText();
    }

    @Test
    void withoutAFallbackNothingChanges() {
        SourceRouter r = router(new Src("local", Set.of("T-1"), false, asked));
        assertThat(from(r, "T-1")).isEqualTo("local");
        assertThatThrownBy(() -> r.fetch(EntityRef.of("trade", "T-2"), AsOf.LATEST).join()).hasCauseInstanceOf(DrishtiException.class)
                .cause().satisfies(e -> assertThat(((DrishtiException) e).errorCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND));
    }

    @Test
    void peersAreAskedOnlyAfterTheLocalSourcesAndInTheirOrder() {
        SourceRouter r = router(new Src("local", Set.of("T-1"), false, asked));
        Src a = new Src("net:a", Set.of(), false, asked);
        Src b = new Src("net:b", Set.of("T-2"), false, asked);
        r.fallback(new SourceRouter.Fallback() {
            public List<SourcePlugin> peers(EntityRef ref, AsOf asOf) {
                return List.of(a, b);
            }
        });
        assertThat(from(r, "T-1")).isEqualTo("local");
        assertThat(asked).containsExactly("local");                         // held here: no peer is asked
        asked.clear();
        assertThat(from(r, "T-2")).isEqualTo("net:b");
        assertThat(asked).containsExactly("local", "net:a", "net:b");      // not held by a: the next
    }

    @Test
    void aKindWithNoLocalSourceIsReadFromAPeer() {
        SourceRouter r = router();
        r.fallback((ref, asOf) -> List.of(new Src("net:a", Set.of("T-9"), false, asked)));
        assertThat(from(r, "T-9")).isEqualTo("net:a");
    }

    @Test
    void aLocalFailureIsNeverReplacedByAPeersDataAndAFailedPeerIsNeverANotFound() {
        SourceRouter r = router(new Src("local", Set.of(), true, asked));
        r.fallback((ref, asOf) -> List.of(new Src("net:a", Set.of("T-1"), false, asked)));
        assertThatThrownBy(() -> r.fetch(EntityRef.of("trade", "T-1"), AsOf.LATEST).join()).isInstanceOf(CompletionException.class)
                .cause().isInstanceOf(SourceFailure.class).satisfies(e -> assertThat(((SourceFailure) e).errorCode()).isEqualTo(ErrorCode.SOURCE_FAILED));
        assertThat(asked).containsExactly("local");

        SourceRouter r2 = router(new Src("local", Set.of(), false, asked));
        Src down = new Src("net:a", Set.of(), true, asked);
        Src empty = new Src("net:b", Set.of(), false, asked);
        Src third = new Src("net:c", Set.of("T-1"), false, asked);
        r2.fallback((ref, asOf) -> List.of(down, empty, third));
        assertThat(from(r2, "T-1")).isEqualTo("net:c");                     // one failure: the read goes on to the others
        r2.fallback((ref, asOf) -> List.of(down, empty));
        assertThatThrownBy(() -> r2.fetch(EntityRef.of("trade", "T-1"), AsOf.LATEST).join()).cause().isInstanceOf(SourceFailure.class)
                .satisfies(e -> assertThat(((SourceFailure) e).errorCode()).isEqualTo(ErrorCode.NET_PEER_FAILED)).hasMessageContaining("net:a".substring(4));
        Src down2 = new Src("net:d", Set.of(), true, asked);
        asked.clear();
        r2.fallback((ref, asOf) -> List.of(down, down2, third));
        assertThatThrownBy(() -> r2.fetch(EntityRef.of("trade", "T-1"), AsOf.LATEST).join()).cause().isInstanceOf(SourceFailure.class);
        assertThat(asked).containsExactly("local", "net:a", "net:d");      // two failures: no more peers are asked
    }

    @Test
    void noPeerToAskButOneHoldsTheKindSaysWhy() {
        SourceRouter r = router(new Src("local", Set.of(), false, asked));
        r.fallback(new SourceRouter.Fallback() {
            public List<SourcePlugin> peers(EntityRef ref, AsOf asOf) {
                return List.of();
            }

            public Optional<DrishtiException> unreachable(EntityRef ref) {
                return Optional.of(new DrishtiException(ErrorCode.NET_PEER_FAILED, "the nodes that hold trade cannot be reached now: n-a (left)"));
            }
        });
        assertThatThrownBy(() -> r.fetch(EntityRef.of("trade", "T-1"), AsOf.LATEST).join()).cause().hasMessageContaining("n-a (left)");
    }
}
