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
package com.ash.drishti.identity.collab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

/**
 * The tamper-evidence chain of a thread's revisions: {@code hash = SHA-256(prevHash, the step's fields)}, the first step of a thread
 * chaining from the thread id. It is evidence of rewriting, not prevention (COLLABORATION.md, Data model).
 */
public final class HashChain {

    private HashChain() {}

    /** What the first step of a thread chains from. */
    public static String genesis(String threadId) {
        return sha(threadId);
    }

    /** The step, chained after {@code prevHash}. */
    public static Revision seal(String prevHash, Revision r) {
        return new Revision(r.commentId(), r.revision(), r.at(), r.actor(), r.action(), r.body(), r.reason(), prevHash, hashOf(prevHash, r));
    }

    /** The next step's time: later than the last step's, so a thread's steps sort by time. */
    public static Instant after(Instant wanted, Instant last) {
        Instant at = wanted.truncatedTo(ChronoUnit.MILLIS);
        return last != null && !at.isAfter(last) ? last.plusMillis(1) : at;
    }

    /** The steps in order: null when the chain holds, else a description of the first break. */
    public static String verify(String threadId, List<Revision> chain) {
        String prev = genesis(threadId);
        for (Revision r : chain) {
            if (!prev.equals(r.prevHash())) {
                return "revision " + r.revision() + " of " + r.commentId() + " does not follow the one before it";
            }
            if (!hashOf(prev, r).equals(r.hash())) {
                return "revision " + r.revision() + " of " + r.commentId() + " was changed";
            }
            prev = r.hash();
        }
        return null;
    }

    private static String hashOf(String prev, Revision r) {
        StringBuilder b = new StringBuilder(prev).append('\u001f');
        for (Object o : new Object[] {r.commentId(), r.revision(), r.at(), r.actor(), r.action(), r.body(), r.reason()}) {
            b.append(o == null ? "" : o).append('\u001f');
        }
        return sha(b.toString());
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
