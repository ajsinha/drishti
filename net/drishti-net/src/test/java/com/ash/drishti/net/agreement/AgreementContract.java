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
package com.ash.drishti.net.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * The contract every {@link Agreement} provider passes (CLUSTER_I5_DESIGN.md section 3: acquire, renew, expire, fence, compare-and-set,
 * listeners, loss of the referee), so no provider can drift. A provider's test extends this class and says how a set of nodes is started,
 * how one crashes and how the referee (the database, a majority of voters) is taken away.
 */
public abstract class AgreementContract {

    /** Three or more nodes of one agreement, for one test. */
    protected interface Nodes extends AutoCloseable {
        Agreement node(String id);

        /** Stops {@code id} as a crashed process: its leases are not released. */
        void crash(String id);

        /** Takes the referee away (the database, a majority of the voters) as seen from node {@code survivor}. */
        void loseReferee(String survivor);

        @Override
        void close();
    }

    /** Nodes {@code ids} with the given lease timings. */
    protected abstract Nodes start(List<String> ids, Duration ttl, Duration renew) throws Exception;

    /** A lease's time-to-live in this suite. */
    protected Duration ttl() {
        return Duration.ofSeconds(2);
    }

    /** How often a holder renews in this suite. */
    protected Duration renew() {
        return Duration.ofMillis(400);
    }

    /** How long the provider may take to see that it cannot decide. */
    protected Duration refereeLoss() {
        return Duration.ofSeconds(5);
    }

    protected static boolean await(Duration max, BooleanSupplier ok) throws InterruptedException {
        long end = System.nanoTime() + max.toNanos();
        while (System.nanoTime() < end) {
            if (ok.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return ok.getAsBoolean();
    }

    private Duration settle() {
        return ttl().multipliedBy(3).plus(renew().multipliedBy(4));
    }

    private static String holder(Agreement a, String lease) {
        return a.leases().holder(lease).map(Leases.Lease::node).orElse("");
    }

    private static void name(Nodes n, List<String> ids, String lease, List<String> order) {
        ids.forEach(id -> n.node(id).leases().candidates(lease, order));
    }

    @Test
    void theFirstLiveCandidateInOrderTakesAFreeLease() throws Exception {
        List<String> ids = List.of("a", "b", "c");
        try (Nodes n = start(ids, ttl(), renew())) {
            name(n, ids, "banking.jobs", List.of("b", "a", "c"));
            assertThat(await(settle(), () -> ids.stream().allMatch(id -> holder(n.node(id), "banking.jobs").equals("b"))))
                    .as("every node sees b, the first candidate, hold the lease").isTrue();
            long term = n.node("b").leases().holder("banking.jobs").orElseThrow().term();
            assertThat(term).isPositive();
            assertThat(n.node("b").leases().stillHeld("banking.jobs", term)).isTrue();
            assertThat(n.node("a").leases().stillHeld("banking.jobs", term)).as("only the holder holds").isFalse();
        }
    }

    @Test
    void aHolderKeepsTheLeaseWhileItRenews() throws Exception {
        List<String> ids = List.of("a", "b", "c");
        try (Nodes n = start(ids, ttl(), renew())) {
            name(n, ids, "feeds", List.of("a", "b", "c"));
            assertThat(await(settle(), () -> holder(n.node("a"), "feeds").equals("a"))).isTrue();
            long term = n.node("a").leases().holder("feeds").orElseThrow().term();
            Thread.sleep(ttl().multipliedBy(3).toMillis());
            assertThat(holder(n.node("b"), "feeds")).isEqualTo("a");
            assertThat(n.node("a").leases().stillHeld("feeds", term)).as("renewed past three time-to-lives, same term").isTrue();
        }
    }

    @Test
    void aCrashedHoldersLeaseGoesToTheNextCandidateWithAHigherTerm() throws Exception {
        List<String> ids = List.of("a", "b", "c");
        try (Nodes n = start(ids, ttl(), renew())) {
            name(n, ids, "feeds", List.of("a", "b", "c"));
            assertThat(await(settle(), () -> holder(n.node("b"), "feeds").equals("a"))).isTrue();
            long before = n.node("a").leases().holder("feeds").orElseThrow().term();
            n.crash("a");
            assertThat(await(settle(), () -> holder(n.node("b"), "feeds").equals("b") && holder(n.node("c"), "feeds").equals("b")))
                    .as("b takes the lease after a's time-to-live").isTrue();
            long after = n.node("b").leases().holder("feeds").orElseThrow().term();
            assertThat(after).isGreaterThan(before);
            assertThat(n.node("b").leases().stillHeld("feeds", after)).isTrue();
            assertThat(n.node("b").leases().stillHeld("feeds", before)).as("an older term is fenced").isFalse();
        }
    }

    @Test
    void aReleasedLeaseGoesToTheNextCandidateBeforeItWouldExpire() throws Exception {
        List<String> ids = List.of("a", "b", "c");
        Duration ttl = ttl().multipliedBy(5);
        try (Nodes n = start(ids, ttl, renew())) {
            name(n, ids, "feeds", List.of("a", "b", "c"));
            assertThat(await(ttl.multipliedBy(2), () -> holder(n.node("b"), "feeds").equals("a"))).isTrue();
            n.node("a").leases().release("feeds");
            assertThat(await(ttl.dividedBy(2), () -> holder(n.node("c"), "feeds").equals("b"))).as("handed over, not expired").isTrue();
        }
    }

    @Test
    void leaseListenersHearEveryChangeOfHolder() throws Exception {
        List<String> ids = List.of("a", "b", "c");
        try (Nodes n = start(ids, ttl(), renew())) {
            List<String> heard = Collections.synchronizedList(new ArrayList<>());
            n.node("c").leases().onChange(heard::add);
            name(n, ids, "eod", List.of("a", "b"));
            assertThat(await(settle(), () -> heard.contains("eod") && holder(n.node("c"), "eod").equals("a"))).isTrue();
            heard.clear();
            n.crash("a");
            assertThat(await(settle(), () -> heard.contains("eod") && holder(n.node("c"), "eod").equals("b"))).isTrue();
        }
    }

    @Test
    void aCompareAndSetWritesOnlyOnTheExpectedVersion() throws Exception {
        List<String> ids = List.of("a", "b", "c");
        try (Nodes n = start(ids, ttl(), renew())) {
            Register r = n.node("a").register();
            assertThat(r.get("placement")).isEmpty();
            assertThat(r.compareAndSet("placement", 1, bytes("v1"), "ada")).as("a key never written has version 0").isFalse();
            assertThat(r.compareAndSet("placement", 0, bytes("v1"), "ada")).isTrue();
            Register.Entry e = n.node("b").register().get("placement").orElseThrow();
            assertThat(e.version()).isEqualTo(1);
            assertThat(e.text()).isEqualTo("v1");
            assertThat(e.by()).isEqualTo("ada");
            assertThat(n.node("c").register().compareAndSet("placement", 0, bytes("v2"), "bob")).as("another write came first").isFalse();
            assertThat(n.node("c").register().compareAndSet("placement", 1, bytes("v2"), "bob")).isTrue();
            assertThat(r.get("placement").orElseThrow().version()).isEqualTo(2);
            assertThatThrownBy(() -> r.compareAndSet("big", 0, new byte[Register.MAX_VALUE_BYTES + 1], "ada"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void everyNodeHearsARegisterWriteUnderItsPrefix() throws Exception {
        List<String> ids = List.of("a", "b", "c");
        try (Nodes n = start(ids, ttl(), renew())) {
            List<List<String>> heard = new ArrayList<>();
            for (String id : ids) {
                List<String> mine = Collections.synchronizedList(new ArrayList<>());
                heard.add(mine);
                n.node(id).register().onChange("switches/", e -> mine.add(e.key() + "@" + e.version()));
            }
            assertThat(n.node("a").register().compareAndSet("switches/var", 0, bytes("on"), "ada")).isTrue();
            assertThat(n.node("a").register().compareAndSet("placement", 0, bytes("p"), "ada")).isTrue();
            assertThat(await(settle(), () -> heard.stream().allMatch(l -> l.contains("switches/var@1")))).isTrue();
            heard.forEach(l -> assertThat(l).doesNotContain("placement@1"));
        }
    }

    @Test
    void withoutTheRefereeAChangeIsRefusedAndReadsGoOn() throws Exception {
        List<String> ids = List.of("a", "b", "c");
        try (Nodes n = start(ids, ttl(), renew())) {
            Register r = n.node("a").register();
            assertThat(r.compareAndSet("net/key-epoch", 0, bytes("1"), "ada")).isTrue();
            assertThat(await(settle(), () -> n.node("a").health().decides())).isTrue();
            n.loseReferee("a");
            assertThatThrownBy(() -> r.compareAndSet("net/key-epoch", 1, bytes("2"), "ada")).isInstanceOf(AgreementUnavailableException.class);
            assertThat(await(refereeLoss().plus(ttl()), () -> !n.node("a").health().decides())).as("health says it cannot decide").isTrue();
            assertThat(n.node("a").health().refusal()).contains("cannot decide");
            assertThat(r.get("net/key-epoch")).as("the last known entry is still read").map(Register.Entry::text).contains("1");
        }
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
