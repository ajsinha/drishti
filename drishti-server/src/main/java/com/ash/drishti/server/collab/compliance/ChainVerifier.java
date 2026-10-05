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
    public record Report(long threads, long threadsOk, long shares, long sharesOk, List<Problem> problems, boolean truncated) {
        @com.fasterxml.jackson.annotation.JsonProperty("ok")
        public boolean ok() {
            return threads == threadsOk && shares == sharesOk;
        }
    }

    private static final int PAGE = 200;

    private final ThreadStore threads;
    private final ShareStore shares;

    public ChainVerifier(ThreadStore threads, ShareStore shares) {
        this.threads = threads;
        this.shares = shares;
    }

    /** The one thread's chain. */
    public ThreadReport verifyThread(String threadId) {
        if (threads.thread(threadId).isEmpty()) {
            throw new DrishtiException(ErrorCode.THREAD_NOT_FOUND, "no thread " + threadId);
        }
        return report(threadId);
    }

    ThreadReport report(String threadId) {
        List<Revision> chain = threads.chain(threadId);
        String problem = HashChain.verify(threadId, chain);
        return new ThreadReport(threadId, problem == null, chain.size(), chain.isEmpty() ? null : chain.get(0).hash(),
                chain.isEmpty() ? null : chain.get(chain.size() - 1).hash(), problem);
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
                if (sh.intact()) {
                    sOk++;
                } else if (problems.size() < maxProblems) {
                    problems.add(new Problem("share", sh.id(), "the share's hash does not match its fields"));
                } else {
                    truncated = true;
                }
            }
        }
        return new Report(t, tOk, s, sOk, problems, truncated);
    }

    private static boolean matches(String k, String e, String kind, String entityId) {
        return (kind == null || kind.equals(k)) && (entityId == null || entityId.equals(e));
    }
}
