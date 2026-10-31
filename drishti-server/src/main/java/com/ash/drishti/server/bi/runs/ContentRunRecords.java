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
package com.ash.drishti.server.bi.runs;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.bi.BiDocs;
import com.ash.drishti.identity.content.ContentStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Run rows in the content store (docs/architecture/BI_DESIGN.md › BI runs: every BI query as a bounded, visible job (built in 2.6.7), section 13.3, DATA_ARCHITECTURE.md › The content store: user content every node reaches, section 9): {@code bi/runs/rows/<id>.json}, one
 * document a run. Every node of a cluster reads the same rows, so a run's status answers from any node, and the lease is a compare-and-set on the
 * row: {@link #renew} writes only while this node still holds it, and {@link #adopt} only while the row is still the dead node's with its lease
 * lapsed, so exactly one node wins. A save from a node that lost the run (another node adopted it: a higher attempt) is dropped.
 * The listings come from a snapshot at most the list TTL old; a single run is always read from the store.
 */
public final class ContentRunRecords implements RunRecords {

    private static final Logger LOG = LoggerFactory.getLogger(ContentRunRecords.class);
    static final String ROWS = "bi/runs/rows/";

    private final BiDocs docs;

    public ContentRunRecords(ContentStore store, long listTtlMs) {
        this.docs = new BiDocs(store, listTtlMs);
    }

    static String key(String id) {
        return ROWS + BiDocs.seg(id) + ".json";
    }

    /** Changes a row as one compare-and-set, again while another writer gets in between; {@code change} returns null to leave it. */
    private boolean change(String id, UnaryOperator<Run> change) {
        ContentStore store = docs.store();
        for (int attempt = 0; ; attempt++) {
            Optional<ContentStore.Item> it = store.get(key(id));
            Run now = it.map(i -> BiDocs.parse(i.bytes(), Run.class)).orElse(null);
            Run next = change.apply(now);
            if (next == null) {
                return false;
            }
            try {
                store.put(key(id), BiDocs.bytes(next), it.map(ContentStore.Item::version).orElse(null));
                docs.changed(key(id));
                return true;
            } catch (DrishtiException e) {
                if (e.errorCode() != ErrorCode.CONTENT_CONFLICT || attempt >= 20) {
                    throw e;
                }
            }
        }
    }

    @Override
    public void save(Run r) {
        boolean[] dropped = {false};
        change(r.id(), now -> {
            if (now != null && now.attempt() > r.attempt() && !now.node().equals(r.node())) {
                dropped[0] = true;                                             // adopted by another node meanwhile: its row wins
                return null;
            }
            return r;
        });
        if (dropped[0]) {
            LOG.info("BI runs: run {} is held by another node now; this node's copy is not written", r.id());
        }
    }

    @Override
    public Optional<Run> get(String id) {
        return docs.peek(key(id), Run.class);
    }

    private List<Run> where(Predicate<Run> p, int limit) {
        return docs.listed(ROWS, Run.class).stream().filter(p)
                .sorted(Comparator.comparing(Run::submittedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed()
                        .thenComparing(Run::id, Comparator.reverseOrder()))
                .limit(limit).toList();
    }

    @Override
    public List<Run> owned(String owner, Instant before, int limit) {
        return where(r -> (owner.equals(r.owner()) || owner.equals(r.runner())) && (before == null || r.submittedAt() != null && r.submittedAt().isBefore(before)), limit);
    }

    @Override
    public List<Run> onNode(String node, List<RunState> states) {
        return where(r -> node.equals(r.node()) && states.contains(r.state()), Integer.MAX_VALUE);
    }

    @Override
    public List<Run> inStates(List<RunState> states, int limit) {
        return where(r -> states.contains(r.state()), limit);
    }

    @Override
    public Optional<Run> doneWithKey(String resultKey) {
        return where(r -> resultKey.equals(r.resultKey()) && r.state() == RunState.DONE, 1).stream().findFirst();
    }

    @Override
    public boolean renew(String id, String node, Instant until) {
        return change(id, r -> {
            if (r == null || !node.equals(r.node())) {
                return null;
            }
            Run.Builder b = r.toBuilder();
            b.leaseUntil = until;
            return b.build();
        });
    }

    @Override
    public boolean adopt(String id, String from, String to, Instant now, String reason) {
        ContentStore store = docs.store();
        Optional<ContentStore.Item> it = store.get(key(id));
        if (it.isEmpty()) {
            return false;
        }
        Run r = BiDocs.parse(it.get().bytes(), Run.class);
        if (!from.equals(r.node()) || !LEASED.contains(r.state()) || r.leaseUntil() == null || !r.leaseUntil().isBefore(now)) {
            return false;
        }
        Run moved = r.state() == RunState.QUEUED ? r : r.moveTo(RunState.QUEUED, null, reason, now);     // running or storing: attempt + 1
        Run.Builder b = moved.toBuilder();
        b.node = to;
        b.attempt = r.attempt() + 1;
        b.reason = reason;
        b.leaseUntil = null;
        try {
            store.put(key(id), BiDocs.bytes(b.build()), it.get().version());      // the lease: exactly one node's write lands
            docs.changed(key(id));
            return true;
        } catch (DrishtiException e) {
            if (e.errorCode() == ErrorCode.CONTENT_CONFLICT) {
                return false;                                                   // another node won, or the old one renewed in time
            }
            throw e;
        }
    }

    /** Run shares in the content store: {@code bi/runs/shares/<run>/<recipient>.json}. */
    public static final class Shares implements RunShares {
        static final String ROOT = "bi/runs/shares/";
        private final BiDocs docs;

        public Shares(ContentStore store) {
            this.docs = new BiDocs(store, 0);
        }

        static String key(String runId, String recipient) {
            return ROOT + BiDocs.seg(runId) + "/" + BiDocs.seg(recipient) + ".json";
        }

        @Override
        public void save(Share s) {
            docs.store().putLatest(key(s.runId(), s.recipient()), BiDocs.bytes(s));
        }

        @Override
        public boolean remove(String runId, String recipient) {
            return docs.delete(key(runId, recipient));
        }

        @Override
        public Optional<Share> get(String runId, String recipient) {
            return docs.peek(key(runId, recipient), Share.class);
        }

        @Override
        public List<Share> of(String runId) {
            return docs.all(ROOT + BiDocs.seg(runId) + "/", Share.class).stream().sorted(Comparator.comparing(Share::recipient)).toList();
        }

        @Override
        public List<Share> to(String recipient, int limit) {
            String tail = "/" + BiDocs.seg(recipient) + ".json";
            List<Share> out = new ArrayList<>();
            for (String k : docs.keys(ROOT)) {
                if (k.endsWith(tail)) {
                    docs.peek(k, Share.class).filter(s -> recipient.equals(s.recipient())).ifPresent(out::add);
                }
            }
            return out.stream().sorted(Comparator.comparing(Share::sharedAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed()).limit(limit).toList();
        }
    }

    /** Run schedules in the content store: {@code bi/runs/schedules/<id>.json}; a due schedule is taken by one node (compare-and-set). */
    public static final class Schedules implements RunSchedules.Store {
        static final String ROOT = "bi/runs/schedules/";
        private final BiDocs docs;

        public Schedules(ContentStore store) {
            this.docs = new BiDocs(store, 0);
        }

        static String key(String id) {
            return ROOT + BiDocs.seg(id) + ".json";
        }

        @Override
        public void save(RunSchedules.Schedule s) {
            docs.store().putLatest(key(s.id()), BiDocs.bytes(s));
        }

        @Override
        public Optional<RunSchedules.Schedule> get(String id) {
            return docs.peek(key(id), RunSchedules.Schedule.class);
        }

        @Override
        public void delete(String id) {
            docs.delete(key(id));
        }

        @Override
        public List<RunSchedules.Schedule> all() {
            return docs.all(ROOT, RunSchedules.Schedule.class);
        }

        @Override
        public boolean claim(RunSchedules.Schedule due, RunSchedules.Schedule next) {
            ContentStore store = docs.store();
            Optional<ContentStore.Item> it = store.get(key(due.id()));
            if (it.isEmpty()) {
                return false;
            }
            RunSchedules.Schedule now = BiDocs.parse(it.get().bytes(), RunSchedules.Schedule.class);
            if (!java.util.Objects.equals(now.nextAt(), due.nextAt()) || !now.enabled()) {
                return false;
            }
            try {
                store.put(key(due.id()), BiDocs.bytes(next), it.get().version());
                return true;
            } catch (DrishtiException e) {
                if (e.errorCode() == ErrorCode.CONTENT_CONFLICT) {
                    return false;
                }
                throw e;
            }
        }
    }
}
