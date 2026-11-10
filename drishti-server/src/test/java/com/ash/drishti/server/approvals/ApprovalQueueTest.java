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
package com.ash.drishti.server.approvals;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.FilePreferenceStore;
import com.ash.drishti.identity.collab.Notice;
import com.ash.drishti.kernel.api.security.Principal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one Approvals queue with fake kinds: every person sees their own queue (inbox, sent, done), due-soon items are pinned first, the
 * maker's own item is never in their inbox, a decision goes to the kind and is audited, a question is a thread on the item that only those who
 * may see the item may read or write, and the maker is told of it without its text.
 */
class ApprovalQueueTest {

    static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    static final Principal ANA = new Principal("ana", List.of("author"));
    static final Principal RUI = new Principal("rui", List.of("approver"));
    static final Principal EVE = new Principal("eve", List.of("viewer"));

    /** A kind whose items are kept here: each item is seen by its maker and by the approvers it names. */
    static final class Fake implements ApprovalSource {
        final String kind;
        final String group;
        final Map<String, String> status = new ConcurrentHashMap<>();
        final Map<String, String> maker = new ConcurrentHashMap<>();
        final Map<String, List<String>> approvers = new ConcurrentHashMap<>();
        final Map<String, LocalDate> due = new ConcurrentHashMap<>();
        final Map<String, Instant> at = new ConcurrentHashMap<>();
        final List<String> decisions = new CopyOnWriteArrayList<>();

        Fake(String kind, String group) {
            this.kind = kind;
            this.group = group;
        }

        void add(String id, String by, List<String> deciders, LocalDate dueOn, Instant when) {
            status.put(id, PENDING);
            maker.put(id, by);
            approvers.put(id, deciders);
            if (dueOn != null) {
                due.put(id, dueOn);
            }
            at.put(id, when);
        }

        @Override
        public String kind() {
            return kind;
        }

        @Override
        public String group() {
            return group;
        }

        @Override
        public String label() {
            return kind.toUpperCase(java.util.Locale.ROOT);
        }

        boolean sees(String id, Principal p) {
            return p.user().equals(maker.get(id)) || approvers.getOrDefault(id, List.of()).contains(p.user());
        }

        Item item(String id, Principal p) {
            boolean mine = p.user().equals(maker.get(id));
            return new Item(kind, id, kind + " " + id, maker.get(id), at.get(id), status.get(id), "why", due.get(id),
                    PENDING.equals(status.get(id)) && approvers.get(id).contains(p.user()) && !mine, mine, mine && PENDING.equals(status.get(id)), "/x", null, null, null);
        }

        @Override
        public List<Item> items(Principal p, int limit) {
            List<Item> out = new ArrayList<>();
            status.keySet().stream().sorted().filter(id -> sees(id, p)).forEach(id -> out.add(item(id, p)));
            return out;
        }

        @Override
        public Detail detail(String id, Principal p) {
            if (!status.containsKey(id) || !sees(id, p)) {
                throw new DrishtiException(ErrorCode.PROPOSAL_NOT_FOUND, "no " + id);
            }
            return new Detail(item(id, p), ApprovalSource.textDiff("a\nb\n", "a\nc\n"), List.of("the effect"), List.of("the rule"), Map.of());
        }

        @Override
        public Item decide(String id, String action, String comment, Map<String, Object> params, Principal p) {
            Item i = detail(id, p).item();
            if (action.equals("withdraw") ? !i.mine() : !i.mayDecide()) {
                throw new DrishtiException(ErrorCode.FOUR_EYES, "not yours to " + action);
            }
            if (action.equals("reject") && (comment == null || comment.isBlank())) {
                throw new DrishtiException(ErrorCode.BAD_REQUEST, "say why");
            }
            status.put(id, action.equals("approve") ? "approved" : action.equals("reject") ? "rejected" : "withdrawn");
            decisions.add(p.user() + " " + action + " " + id + " " + params);
            return item(id, p);
        }
    }

    final Fake sutras = new Fake("sutra", "screens");
    final Fake reports = new Fake("var-report", "risk");
    final List<String> audit = new CopyOnWriteArrayList<>();
    final List<Notice> told = new CopyOnWriteArrayList<>();
    ApprovalQueue queue;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        AuditLog log = new AuditLog() {
            @Override
            public void record(String actor, String action, String subject, String detail) {
                audit.add(actor + " " + action + " " + subject);
            }

            @Override
            public List<Event> recent(int limit, String subject) {
                return List.of();
            }
        };
        queue = new ApprovalQueue(List.of(sutras, reports), new FilePreferenceStore(dir, 65_536, 50), log, ApprovalProperties.defaults(),
                Clock.fixed(NOW, ZoneOffset.UTC), told::add);
        sutras.add("P-1", "ana", List.of("rui"), null, NOW.minusSeconds(3600));
        sutras.add("P-2", "ana", List.of("rui"), null, NOW.minusSeconds(60));
        reports.add("annual:FY2026:v1", "rao", List.of("rui", "ana"), LocalDate.of(2026, 11, 9), NOW.minusSeconds(86_400));
        reports.add("annual:FY2025:v3", "rao", List.of("rui"), LocalDate.of(2026, 12, 31), NOW.minusSeconds(30));
    }

    static List<String> ids(ApprovalQueue.Listing l) {
        return l.items().stream().map(e -> e.item().id()).toList();
    }

    @Test
    void twoPeopleEachSeeTheirOwnQueueWithDueSoonPinnedFirst() {
        ApprovalQueue.Listing rui = queue.list(RUI, "inbox", null, null, 100);
        assertThat(ids(rui)).containsExactly("annual:FY2026:v1", "annual:FY2025:v3", "P-2", "P-1");          // due in 2 days first, then newest
        assertThat(rui.items().get(0).dueSoon()).isTrue();
        assertThat(rui.inbox()).isEqualTo(4);
        assertThat(rui.dueSoon()).isEqualTo(1);
        ApprovalQueue.Listing ana = queue.list(ANA, "inbox", null, null, 100);
        assertThat(ids(ana)).containsExactly("annual:FY2026:v1");                                           // never her own Sutras
        assertThat(ids(queue.list(ANA, "sent", null, null, 100))).containsExactlyInAnyOrder("P-1", "P-2");
        assertThat(ids(queue.list(EVE, "all", null, null, 100))).isEmpty();                                   // nothing of anyone else
        assertThat(queue.count(EVE)).containsEntry("inbox", 0);
        assertThat(queue.count(RUI)).containsEntry("inbox", 4).containsEntry("dueSoon", 1);
        assertThat(ids(queue.list(RUI, "inbox", "risk", null, 100))).containsExactly("annual:FY2026:v1", "annual:FY2025:v3");
        assertThat(ids(queue.list(RUI, "inbox", null, "sutra", 100))).containsExactly("P-2", "P-1");
        assertThatThrownBy(() -> queue.list(RUI, "everything", null, null, 100)).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> queue.list(RUI, "inbox", "money", null, 100)).isInstanceOf(DrishtiException.class);
    }

    @Test
    void anItemOpensWithItsDiffEffectRulesAndTheActionsThePersonHas() {
        ApprovalQueue.Opened rui = queue.open("sutra", "P-1", RUI);
        assertThat(rui.actions()).containsExactly("approve", "reject", "ask");
        assertThat(rui.detail().diff().get("format")).isEqualTo("lines");
        assertThat(rui.detail().effect()).containsExactly("the effect");
        assertThat(rui.detail().rules()).containsExactly("the rule");
        assertThat(queue.open("sutra", "P-1", ANA).actions()).containsExactly("withdraw", "ask");               // the maker withdraws, never approves
        assertThatThrownBy(() -> queue.open("sutra", "P-1", EVE)).isInstanceOf(DrishtiException.class);
        assertThatThrownBy(() -> queue.open("nope", "P-1", RUI)).isInstanceOf(DrishtiException.class);
    }

    @Test
    void aDecisionGoesToItsKindAndIsAuditedAndTheMakerNeverApprovesTheirOwn() {
        assertThatThrownBy(() -> queue.decide("sutra", "P-1", "approve", "mine", Map.of(), ANA)).isInstanceOf(DrishtiException.class);
        assertThat(audit).contains("ana approval.approve.refused sutra/P-1");
        assertThatThrownBy(() -> queue.decide("sutra", "P-1", "reject", " ", Map.of(), RUI)).hasMessageContaining("say why");
        assertThatThrownBy(() -> queue.decide("sutra", "P-1", "delete", null, Map.of(), RUI)).hasMessageContaining("approve, reject or withdraw");
        assertThat(queue.decide("sutra", "P-1", "approve", "fine", Map.of("scope", "everyone"), RUI).status()).isEqualTo("approved");
        assertThat(sutras.decisions).containsExactly("rui approve P-1 {scope=everyone}");
        assertThat(audit).contains("rui approval.approve sutra/P-1");
        assertThat(queue.decide("sutra", "P-2", "withdraw", null, Map.of(), ANA).status()).isEqualTo("withdrawn");
        assertThat(ids(queue.list(RUI, "inbox", null, "sutra", 100))).isEmpty();
        assertThat(ids(queue.list(RUI, "done", null, "sutra", 100))).containsExactlyInAnyOrder("P-1", "P-2");
    }

    @Test
    void aQuestionIsAThreadOnTheItemForThoseWhoMaySeeItAndTheMakerIsToldWithoutTheText() {
        List<ApprovalQueue.Post> posts = queue.ask("sutra", "P-1", "Why the new title?", RUI);
        assertThat(posts).extracting(ApprovalQueue.Post::by).containsExactly("rui");
        assertThat(told).hasSize(1);
        assertThat(told.get(0).username()).isEqualTo("ana");
        assertThat(told.get(0).entityId()).isEqualTo("/approvals/sutra/P-1");
        assertThat(told.get(0).panelId()).doesNotContain("Why the new title");
        queue.ask("sutra", "P-1", "The desk asked for it.", ANA);                                              // the maker answers: the asker is told
        assertThat(told).hasSize(2);
        assertThat(told.get(1).username()).isEqualTo("rui");
        assertThat(queue.open("sutra", "P-1", RUI).questions()).extracting(ApprovalQueue.Post::text)
                .containsExactly("Why the new title?", "The desk asked for it.");
        assertThat(queue.list(RUI, "inbox", null, "sutra", 100).items().stream().filter(e -> e.item().id().equals("P-1")).findFirst().orElseThrow().questions())
                .isEqualTo(2);
        assertThatThrownBy(() -> queue.ask("sutra", "P-1", "Can I see it?", EVE)).isInstanceOf(DrishtiException.class);   // not shown to her: no thread
        assertThatThrownBy(() -> queue.ask("sutra", "P-1", " ", RUI)).hasMessageContaining("some text");
        assertThat(audit).contains("rui approval.ask sutra/P-1", "ana approval.ask sutra/P-1");
    }

    @Test
    void theKindsAreCheckedWhenTheQueueIsBuilt(@TempDir Path dir) {
        AuditLog none = new AuditLog() {
            @Override
            public void record(String actor, String action, String subject, String detail) {
            }

            @Override
            public List<Event> recent(int limit, String subject) {
                return List.of();
            }
        };
        FilePreferenceStore store = new FilePreferenceStore(dir, 65_536, 50);
        assertThatThrownBy(() -> new ApprovalQueue(List.of(sutras, new Fake("sutra", "data")), store, none, ApprovalProperties.defaults(), Clock.systemUTC(), n -> { }))
                .hasMessageContaining("two approval sources");
        assertThatThrownBy(() -> new ApprovalQueue(List.of(new Fake("x", "money")), store, none, ApprovalProperties.defaults(), Clock.systemUTC(), n -> { }))
                .hasMessageContaining("group is one of");
        assertThatThrownBy(() -> new ApprovalQueue(List.of(new Fake("Bad Kind", "data")), store, none, ApprovalProperties.defaults(), Clock.systemUTC(), n -> { }))
                .hasMessageContaining("lower-case");
    }
}
