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
package com.ash.drishti.server.api;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;

/**
 * The log of expected refusals about another node of the cluster (SOAK-06): {@code DRS-5052} (a node that holds the data is down, or failed),
 * {@code DRS-5053} and {@code DRS-1014} (it did not answer in time). They are documented answers, not faults of this server, so each is one
 * short WARN line without a stack trace, and only the first time in a minute it says a thing about the nodes (drishti-c not answering,
 * failed, suspect, dead: one line each); the same refusal again is counted, and the count is logged once a minute while refusals keep
 * coming. Refusals that alternate between two things said about one node (a page failed at the node, a history not answered by it) are
 * one line each from the first refusal, not a line at every alternation (SOAK2-05: 38 lines in the 2 s before the node was suspect).
 * Before SOAK-06, every one was logged with a full stack trace: about 11 MB a minute per server while one node was down. Unexpected errors
 * keep their stack traces (the caller logs them).
 */
final class RefusalLog {

    /** A node and its state as a refusal names it: {@code drishti-c (dead)}, {@code b (not answering: the connection failed)}. */
    private static final Pattern STATE = Pattern.compile(
            "([A-Za-z0-9][\\w.-]*) \\((dead|suspect|alive|rejoining|drained|left|not answering[^)]*)\\)");
    /** A node named without a state: {@code the node drishti-c that holds trade failed reading …}, {@code the node b did not answer …}. */
    private static final Pattern NODE = Pattern.compile("the node ([A-Za-z0-9][\\w.-]*)");
    private static final Set<String> EXPECTED = Set.of(ErrorCode.NET_PEER_FAILED.code(), ErrorCode.NET_PEER_TIMEOUT.code(),
            ErrorCode.HISTORY_TIMEOUT.code());

    private final Logger log;
    private final LongSupplier clock;
    private final long everyMs;
    private final Map<String, Seen> byNodes = new ConcurrentHashMap<>();

    /**
     * What was logged for one set of nodes: when each thing said about them was last logged on a line of its own, when the last line was
     * logged, and how many refusals since were only counted.
     */
    private static final class Seen {
        final Map<String, Long> states = new java.util.HashMap<>();
        long loggedAt;
        long counted;
    }

    RefusalLog(Logger log, LongSupplier clock, long everyMs) {
        this.log = log;
        this.clock = clock;
        this.everyMs = everyMs;
    }

    /** True when the error is an expected refusal about another node (or a time-out): logged here, never with a stack trace. */
    static boolean expected(DrishtiException e) {
        return EXPECTED.contains(e.errorCode().code());
    }

    /**
     * Logs the refusal when what it says about the nodes was not logged in the last interval, else counts it (and logs the count once every
     * interval).
     */
    void refused(DrishtiException e) {
        String msg = String.valueOf(e.getMessage());
        Set<String> nodes = new TreeSet<>();
        Set<String> states = new TreeSet<>();
        Matcher m = STATE.matcher(msg);
        while (m.find()) {
            nodes.add(m.group(1));
            states.add(m.group(1) + " (" + m.group(2) + ")");
        }
        if (nodes.isEmpty()) {
            Matcher n = NODE.matcher(msg);
            while (n.find()) {
                nodes.add(n.group(1));
                states.add(n.group(1) + " (" + (e.errorCode() == ErrorCode.NET_PEER_FAILED ? "failed" : "slow") + ")");
            }
        }
        String key = e.errorCode().code() + " " + (nodes.isEmpty() ? "-" : String.join(",", nodes));
        String state = states.isEmpty() ? "-" : String.join(", ", states);
        long now = clock.getAsLong();
        if (byNodes.size() > 1_000) {
            byNodes.clear();                                       // never grows without bound (node names come from messages)
        }
        Seen s = byNodes.computeIfAbsent(key, k -> new Seen());
        String line = null;
        synchronized (s) {
            Long said = s.states.get(state);
            if (s.counted > 0 && now - s.loggedAt >= everyMs) {         // the minute's count first
                line = "request refused " + (s.counted + 1) + " times in the last " + Math.max(1, (now - s.loggedAt) / 1000) + " s naming "
                        + state + "; the latest: " + msg;
                s.states.put(state, now);
                s.loggedAt = now;
                s.counted = 0;
            } else if (said == null || now - said >= everyMs) {          // a thing not said about these nodes in the last minute
                line = "request refused: " + msg + (s.counted > 0 ? " (" + s.counted + " more refusals before it were counted, not logged)" : "")
                        + "; repeats are counted and logged once a minute";
                if (s.states.size() > 32) {
                    s.states.clear();
                }
                s.states.put(state, now);
                s.loggedAt = now;
                s.counted = 0;
            } else {
                s.counted++;
            }
        }
        if (line != null) {
            log.warn(line);
        }
    }
}
