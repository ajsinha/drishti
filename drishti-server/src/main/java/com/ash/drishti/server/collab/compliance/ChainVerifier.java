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
package com.ash.drishti.server.collab.compliance;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.collab.CommentThread;
import com.ash.drishti.identity.collab.HashChain;
import com.ash.drishti.identity.collab.Revision;
import com.ash.drishti.identity.collab.Share;
import com.ash.drishti.identity.collab.ShareStore;
import com.ash.drishti.identity.collab.ThreadStore;
import com.ash.drishti.identity.collab.Comment;
import com.ash.drishti.identity.collab.Hold;
import com.ash.drishti.identity.collab.HoldStore;
import com.ash.drishti.identity.collab.Seal;
import com.ash.drishti.identity.collab.Seals;
import java.util.ArrayList;
import java.util.List;

/**
 * Tamper evidence: recomputes a thread's hash chain (SHA-256 per revision, chained per thread) and a share's own hash. It finds a
 * changed or removed step; it does not prevent rewriting the whole database, which is why exports carry the hashes
 * (COLLABORATION.md, Data model).
 */
public final class ChainVerifier {

    /** One thread's chain: {@code problem} is null when it holds. */
    public record ThreadReport(String thread, boolean ok, int steps, String firstHash, String lastHash, String problem) {}

    /** A thread or share that failed. */
    public record Problem(String type, String id, String problem) {}

    /** A whole-record report; {@code problems} is capped at {@code maxProblems} ({@code truncated}). */
    public record Report(long threads, long threadsOk, long shares, long sharesOk, long holds, long holdsOk, boolean auditOk,
            List<Problem> problems, boolean truncated) {
        @com.fasterxml.jackson.annotation.JsonProperty("ok")
        public boolean ok() {
            return threads == threadsOk && shares == sharesOk && holds == holdsOk && auditOk;
        }
    }

    private static final int PAGE = 200;

    private final ThreadStore threads;
    private final ShareStore shares;

    private final HoldStore holds;
    private final Seal.Store seals;
    private final com.ash.drishti.identity.JpaAuditLog audit;

    public ChainVerifier(ThreadStore threads, ShareStore shares, HoldStore holds, Seal.Store seals, com.ash.drishti.identity.JpaAuditLog audit) {
        this.threads = threads;
        this.shares = shares;
        this.holds = holds;
        this.seals = seals;
        this.audit = audit;
    }

    /** The one thread's chain. */
    public ThreadReport verifyThread(String threadId) {
        if (threads.thread(threadId).isEmpty()) {
            throw new DrishtiException(ErrorCode.THREAD_NOT_FOUND, "no thread " + threadId);
        }
        return report(threadId);
    }

    ThreadReport report(String threadId) {
        try {
            List<Revision> chain = threads.chain(threadId);
            List<String> problems = new ArrayList<>();
            String broken = HashChain.verify(threadId, chain);
            if (broken != null) {
                problems.add(broken);
            }
            seals(threadId, chain, problems);
            comments(threadId, chain, problems);
            String problem = problems.isEmpty() ? null : String.join("; ", problems);
            return new ThreadReport(threadId, problem == null, chain.size(), chain.isEmpty() ? null : chain.get(0).hash(),
                    chain.isEmpty() ? null : chain.get(chain.size() - 1).hash(), problem);
        } catch (RuntimeException e) {                   // a row that cannot be read is a finding, not a failure of the check
            return new ThreadReport(threadId, false, 0, null, null, "unreadable rows: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** The thread's head: how many revisions were written and the last one's hash (finds a removed newest revision). */
    private void seals(String threadId, List<Revision> chain, List<String> problems) {
        seals.get("thread:" + threadId).ifPresent(seal -> {
            String last = chain.isEmpty() ? "" : chain.get(chain.size() - 1).hash();
            if (seal.count() != chain.size() || !seal.hash().equals(last)) {
                problems.add("the thread has " + chain.size() + " revisions but " + seal.count() + " were written (the newest were removed or replaced)");
            }
        });
    }

    /** The live comment rows against the revisions that made them: text, author, state and revision number, and the row's own seal. */
    private void comments(String threadId, List<Revision> chain, List<String> problems) {
        for (Comment c : threads.comments(threadId)) {
            Revision first = null;
            Revision last = null;
            String body = null;
            String state = Comment.LIVE;
            for (Revision r : chain) {
                if (!r.commentId().equals(c.id())) {
                    continue;
                }
                first = first == null ? r : first;
                last = r;
                if (r.body() != null) {
                    body = r.body();
                }
                state = switch (r.action()) {
                    case Revision.RETRACTED -> Comment.RETRACTED;
                    case Revision.HIDDEN -> Comment.HIDDEN;
                    case Revision.UNHIDDEN -> Comment.LIVE;
                    default -> state;
                };
            }
            if (last == null) {
                problems.add("comment " + c.id() + " has no revisions");
            } else if (last.revision() != c.revision()) {
                problems.add("comment " + c.id() + " is at revision " + c.revision() + " but its newest revision is " + last.revision());
            } else if (!java.util.Objects.equals(body, c.body())) {
                problems.add("the text of comment " + c.id() + " is not the text of its latest revision");
            } else if (!first.actor().equals(c.author())) {
                problems.add("the author of comment " + c.id() + " is not who wrote its first revision");
            } else if (!state.equals(c.state())) {
                problems.add("the state of comment " + c.id() + " (" + c.state() + ") is not what its revisions say (" + state + ")");
            }
            seals.get("comment:" + c.id()).ifPresent(seal -> {
                if (!seal.hash().equals(Seals.commentHash(c))) {
                    problems.add("comment " + c.id() + " was changed outside Drishti (text, author, state or masked ranges)");
                }
            });
        }
    }

    private long holds(List<Problem> problems, int max) {
        long ok = 0;
        List<Hold> all = holds.list(false);
        for (Hold h : all) {
            var seal = seals.get("hold:" + h.id());
            if (seal.isEmpty() || seal.get().hash().equals(Seals.holdHash(h))) {
                ok++;
            } else if (problems.size() < max) {
                problems.add(new Problem("hold", Long.toString(h.id()), "the hold was changed outside Drishti"));
            }
        }
        var head = seals.get("holds:head");
        if (head.isPresent() && head.get().count() != all.size() && problems.size() < max) {
            problems.add(new Problem("hold", "head", "holds were removed: " + head.get().count() + " were placed, " + all.size() + " found"));
            ok--;
        }
        return ok;
    }

    /** Every thread and share (optionally only one entity's), in bounded memory. */
    public Report verifyAll(String kind, String entityId, int maxProblems) {
        long t = 0, tOk = 0, s = 0, sOk = 0;
        List<Problem> problems = new ArrayList<>();
        boolean truncated = false;
        for (String after = null; ; ) {
            List<CommentThread> page = threads.page(after, PAGE);
            if (page.isEmpty()) {
                break;
            }
            for (CommentThread th : page) {
                after = th.id();
                if (!matches(th.kind(), th.entityId(), kind, entityId)) {
                    continue;
                }
                t++;
                ThreadReport r = report(th.id());
                if (r.ok()) {
                    tOk++;
                } else if (problems.size() < maxProblems) {
                    problems.add(new Problem("thread", th.id(), r.problem()));
                } else {
                    truncated = true;
                }
            }
        }
        for (String after = null; ; ) {
            List<Share> page = shares.page(after, PAGE);
            if (page.isEmpty()) {
                break;
            }
            for (Share sh : page) {
                after = sh.id();
                if (!matches(sh.kind(), sh.entityId(), kind, entityId)) {
                    continue;
                }
                s++;
                if (safeIntact(sh)) {
                    sOk++;
                } else if (problems.size() < maxProblems) {
                    problems.add(new Problem("share", sh.id(), "the share's hash does not match its fields"));
                } else {
                    truncated = true;
                }
            }
        }
        long hn = holds.list(false).size();
        long hOk = holds(problems, maxProblems);
        List<String> auditProblems = audit == null ? List.of() : audit.verify(maxProblems);
        for (String a : auditProblems) {
            if (problems.size() < maxProblems) {
                problems.add(new Problem("audit", "audit", a));
            }
        }
        return new Report(t, tOk, s, sOk, hn, Math.min(hOk, hn), auditProblems.isEmpty(), problems, truncated);
    }

    private static boolean safeIntact(Share sh) {
        try {
            return sh.intact();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean matches(String k, String e, String kind, String entityId) {
        return (kind == null || kind.equals(k)) && (entityId == null || entityId.equals(e));
    }
}
