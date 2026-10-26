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
package com.ash.drishti.server.edit;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.PluginManifest;
import com.ash.drishti.api.SourceCapabilities;
import com.ash.drishti.api.SourceContext;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.write.WriteCommand;
import com.ash.drishti.api.write.WriteResult;
import com.ash.drishti.identity.collab.Ulid;
import com.ash.drishti.packs.ConnectorFiles;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Source commands through the outbox and connector {@code writes:} (WRITE_BACK.md sections 5.3, 5.4 and W4): a command pending when its
 * lane's owner stops is sent by the next owner with the same change id and the source applies it once; a stale version comes back
 * conflicted and the pending overlay is withdrawn; a command without its guard or idempotency clause, or naming an unknown parameter,
 * is refused at load (the connector read-only for it); a plugin that cannot write is refused; writes stay off until switched on; the
 * auto rule picks the edit log's store.
 */
class OutboxTest {

    @TempDir
    Path dir;
    HikariDataSource ds;

    @AfterEach
    void close() {
        if (ds != null) {
            ds.close();
        }
    }

    /** A source that applies a change id once and refuses a stale version. */
    static class FakeSource implements SourcePlugin {
        final Map<String, Object> applied = new ConcurrentHashMap<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong(7);

        @Override
        public PluginManifest manifest() {
            return new PluginManifest("fake", "1.0", Set.of("trade"), new SourceCapabilities(false, false, false, false));
        }

        @Override
        public void start(SourceContext context) {
        }

        @Override
        public Optional<EntityDocument> fetch(EntityRef ref) {
            return Optional.empty();
        }

        @Override
        public Optional<String> writeKey() {
            return Optional.of("fake");
        }

        @Override
        public List<String> checkWrite(String command, Map<String, Object> block, Set<String> known) {
            return block.containsKey("guard") && block.containsKey("idempotent") ? List.of() : List.of(command + ": the guard and idempotency are required");
        }

        @Override
        public WriteResult write(WriteCommand c) {
            calls.add(c.changeId());
            if (applied.containsKey(c.changeId())) {
                return WriteResult.already();
            }
            Object based = c.params().get("basedOn");
            if (based != null && ((Number) based).longValue() != version.get()) {
                return WriteResult.conflicted("version " + version.get() + ", not " + based);
            }
            applied.put(c.changeId(), c.params().get("value"));
            version.incrementAndGet();
            return WriteResult.applied();
        }
    }

    static final com.ash.drishti.identity.AuditLog AUDIT = new com.ash.drishti.identity.AuditLog() {
        @Override
        public void record(String actor, String action, String subject, String detail) {
        }

        @Override
        public List<Event> recent(int limit, String subject) {
            return List.of();
        }
    };

    static ConnectorFiles.Definition definition(Map<String, Object> writes) {
        return new ConnectorFiles.Definition("trade-murex", "fake", "true", List.of("trade"), "", Map.of(), "", "", null, Map.of(), Map.of(), writes);
    }

    static final Map<String, Object> AMEND = Map.of("amend-notional", Map.of("kind", "trade", "fake", Map.of("guard", 1, "idempotent", 1), "confirm", "reply"));

    static Change change(Long basedOn) {
        return new Change(Ulid.next("chg-"), Change.keyOf("trade", "MX-1"), "trade", "MX-1", LocalDate.of(2026, 10, 23),
                List.of(new Change.Item("$.NOTIONAL", 1, 2)), basedOn, "source", "trade-murex", "amend-notional", Map.of(), "pilot", "asha",
                Instant.now(), 0, Map.of(), false);
    }

    @Test
    void aCommandPendingWhenItsOwnerStopsIsSentOnceByTheNextOwner() throws Exception {
        ds = EditTestDb.sqlite(dir);
        EditLog log = new EditLog(ds);
        EditRecords records = new EditRecords(ds);
        FakeSource source = new FakeSource();
        ConnectorWrites writes = new ConnectorWrites(n -> definition(AMEND), n -> Optional.of(source), null).switchedBy(n -> true);
        Change c = change(7L);
        int lanes = 4;
        int lane = Lanes.laneOf(c.key(), lanes);
        log.ensureLanes(lanes);
        long e1 = log.claim(lane, "n1", 50).orElseThrow();
        log.append(c, lane, "n1", e1, 100);
        EditLog.Row row = log.unapplied(lane, 1).get(0);
        log.finish(row, "n1", e1, EditLog.APPLIED, null, EditLog.CMD_PENDING, cn -> records.overlay(cn, new EditRecords.Overlay(c.id(), "$.NOTIONAL",
                "trade", "MX-1", 2, 1, c.businessDate(), null, EditRecords.ACTIVE, "asha", Instant.now())));
        // n1 "sent" it and died before the confirmation was recorded: the source applied it, the log still says sent
        source.write(new WriteCommand("amend-notional", Map.of(), Outbox.params(c, c.items().get(0), c.id())));
        log.command(log.get(c.id()).orElseThrow(), "n1", e1, EditLog.CMD_SENT, null, true, null);
        Thread.sleep(120);
        Set<Integer> all = IntStream.range(0, lanes).boxed().collect(Collectors.toSet());
        List<Boolean> ended = new CopyOnWriteArrayList<>();
        try (Lanes l = new Lanes(log, EditSettings.defaults().withEnabled(true).withLanes(lanes), "n2", (r, n, e) -> { }, () -> all);
                Outbox o = new Outbox(log, records, writes, l, AUDIT, (r, ok, why) -> ended.add(ok), () -> Duration.ofMillis(100))) {
            l.start();
            o.start();
            EditTriggersTest.waitFor(() -> EditLog.CMD_APPLIED.equals(log.get(c.id()).orElseThrow().command()));
        }
        assertThat(source.applied).as("applied once at the source").hasSize(1);
        assertThat(source.calls).as("resent with the same change id").containsOnly(c.id());
        assertThat(log.get(c.id()).orElseThrow().commandReason()).contains("already");
        assertThat(ended).containsExactly(true);
    }

    @Test
    void aStaleVersionIsConflictedAtTheSourceAndThePendingOverlayIsWithdrawn() throws Exception {
        ds = EditTestDb.sqlite(dir);
        EditLog log = new EditLog(ds);
        EditRecords records = new EditRecords(ds);
        FakeSource source = new FakeSource();
        ConnectorWrites writes = new ConnectorWrites(n -> definition(AMEND), n -> Optional.of(source), null).switchedBy(n -> true);
        Change c = change(3L);                                          // the source is at version 7
        int lanes = 4;
        int lane = Lanes.laneOf(c.key(), lanes);
        Set<Integer> all = IntStream.range(0, lanes).boxed().collect(Collectors.toSet());
        List<String> why = new CopyOnWriteArrayList<>();
        try (Lanes l = new Lanes(log, EditSettings.defaults().withEnabled(true).withLanes(lanes), "n1", (r, n, e) -> log.finish(r, n, e, EditLog.APPLIED, null,
                EditLog.CMD_PENDING, cn -> records.overlay(cn, new EditRecords.Overlay(r.changeId(), "$.NOTIONAL", "trade", "MX-1", 2, 1, r.change().businessDate(),
                        null, EditRecords.ACTIVE, "asha", Instant.now()))), () -> all);
                Outbox o = new Outbox(log, records, writes, l, AUDIT, (r, ok, w) -> why.add(ok + " " + w), () -> Duration.ofMillis(100))) {
            l.start();
            o.start();
            l.append(c);
            EditTriggersTest.waitFor(() -> EditLog.CMD_REFUSED.equals(log.get(c.id()).map(EditLog.Row::command).orElse(null)));
        }
        assertThat(log.get(c.id()).orElseThrow().commandReason()).startsWith("conflicted at the source");
        records.forgetAll();
        assertThat(records.overlays("trade", "MX-1")).as("the pending value is withdrawn").isEmpty();
        assertThat(why).singleElement().asString().startsWith("false conflicted");
        assertThat(lane).isBetween(0, 3);
    }

    @Test
    void aCommandWithoutItsGuardOrIdempotencyIsRefusedAtLoadAndWritesAreOffUntilSwitchedOn() {
        FakeSource source = new FakeSource();
        Map<String, Object> bad = Map.of("amend-notional", Map.of("kind", "trade", "fake", Map.of("guard", 1)), "Bad Name", Map.of("kind", "trade"));
        Map<String, List<String>> problems = ConnectorWrites.problems(definition(bad), Optional.of(source));
        assertThat(problems.get("amend-notional")).anyMatch(p -> p.contains("guard and idempotency"));
        assertThat(problems.get("Bad Name")).anyMatch(p -> p.contains("lower case"));
        assertThat(ConnectorWrites.problems(definition(AMEND), Optional.of(source))).isEmpty();
        SourcePlugin readOnly = new FakeSource() {
            @Override
            public Optional<String> writeKey() {
                return Optional.empty();
            }
        };
        assertThat(ConnectorWrites.problems(definition(AMEND), Optional.of(readOnly)).get("amend-notional")).anyMatch(p -> p.contains("cannot write"));
        ConnectorWrites off = new ConnectorWrites(n -> definition(AMEND), n -> Optional.of(source), null);
        assertThat(off.unusable("trade-murex", "amend-notional", "trade")).get().asString().contains("writes are off");
        ConnectorWrites refused = new ConnectorWrites(n -> definition(bad), n -> Optional.of(source), null);
        assertThat(refused.unusable("trade-murex", "amend-notional", "trade")).get().asString().contains("is refused");
    }

    @Test
    void theAutoRulePicksTheStoreAndAnExplicitValueIsChecked() {
        EditSettings s = EditSettings.defaults().withEnabled(true);
        assertThat(EditCluster.plan(s, true, true, "n1").mode()).isEqualTo(EditCluster.Mode.SHARED);
        assertThat(EditCluster.plan(s, false, false, "n1").mode()).isEqualTo(EditCluster.Mode.SINGLE);
        assertThat(EditCluster.plan(s, false, true, "n1").mode()).isEqualTo(EditCluster.Mode.REFUSED);
        assertThat(EditCluster.plan(s.withPrimary("n1", "auto"), false, true, "n1").mode()).isEqualTo(EditCluster.Mode.PRIMARY);
        assertThat(EditCluster.plan(s.withPrimary("n1", "auto"), false, true, "n2").mode()).isEqualTo(EditCluster.Mode.FOLLOWER);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EditCluster.plan(s.withPrimary("", "postgres"), false, false, "n1"))
                .hasMessageContaining("SQLite");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EditCluster.plan(s.withPrimary("", "sqlite"), false, true, "n1"))
                .hasMessageContaining("edit.primary");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.withPrimary("", "s3")).hasMessageContaining("not built");
    }
}
