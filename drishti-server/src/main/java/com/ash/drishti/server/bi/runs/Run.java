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

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * One BI run: the {@code drishti_bi_run} row (docs/architecture/BI_RUNS.md section 3.4), immutable. A state change is {@link #moveTo}, which checks
 * the transition against {@link RunState}'s table and stamps the times; nothing else changes the state. No data value is ever held here:
 * {@code question} and {@code params} carry names, operators and the values the reader typed into filters.
 *
 * @param id         {@code r-<node>-<ulid>} ({@link RunId}): the node that owns it is in the id
 * @param runner     the user it is computed as
 * @param owner      who sees and manages it (the runner, or a schedule's or subscription's owner)
 * @param workspace  the item's workspace, for the workspace's Runs tab (null: none)
 * @param sourceType {@code report | board | mq | sql | statement | subscription | schedule}
 * @param question   the canonical MQ (JSON) that ran: replayable
 * @param params     slicers, filters, parameters, drill path (JSON, as the reader sent them)
 * @param asOf       the resolved dates (a JSON list, or {@code from..to})
 * @param dataVersion the hash of the data version vector ({@link DataVersionVector#hash()})
 * @param fingerprint the plan hash
 * @param signature  the runner's SecuritySignature
 * @param resultKey  {@link ResultKey} (null until planned, or for a source with no data version that still stores for its runner)
 * @param engine     {@code local | split | external:<name>}
 * @param progress   0..1, null when unknown (measured, never guessed: section 4.4)
 * @param estimate   the estimate as JSON ({@link RunEstimate})
 * @param expiresAt  null while promoted or pinned
 */
public record Run(String id, String runner, String owner, String workspace, String sourceType, String sourceId, String sourceRef, String question,
                  String params, String asOf, String dataVersion, String fingerprint, String signature, String resultKey, Tier tier, String engine,
                  RunState state, Double progress, String phase, String estimate, Long rowsOut, Long bytesOut, String code, String reason, String node,
                  int attempt, Instant leaseUntil, Instant submittedAt, Instant startedAt, Instant finishedAt, Instant expiresAt, String rerunOf,
                  String sharedFrom, String groupId, String promotedTo) {

    /** The source types a run may come from (the {@code source_type} column). */
    public static final Set<String> SOURCE_TYPES = Set.of("report", "board", "mq", "sql", "statement", "subscription", "schedule");

    public Run {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(runner, "runner");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(tier, "tier");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(estimate, "estimate");
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(submittedAt, "submittedAt");
        if (!SOURCE_TYPES.contains(sourceType)) {
            throw new IllegalArgumentException("not a run source type: " + sourceType);
        }
        if (progress != null && (progress < 0 || progress > 1)) {
            throw new IllegalArgumentException("progress is 0..1, not " + progress);
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt starts at 1");
        }
    }

    /** A run as the planner leaves it: {@code planned}, attempt 1, nothing started. */
    public static Run planned(String id, String runner, String owner, String sourceType, String question, String fingerprint, String signature,
                              Tier tier, String engine, String estimate, String node, Instant now) {
        return new Run(id, runner, owner, null, sourceType, null, null, question, null, null, null, fingerprint, signature, null, tier, engine,
                RunState.PLANNED, null, null, estimate, null, null, null, null, node, 1, null, now, null, null, null, null, null, null, null);
    }

    /**
     * This run in state {@code to}, checked against the transition table (an illegal one throws {@link RunState.IllegalTransition}). Entering
     * {@code running} stamps {@code startedAt} (the first time); entering a finished state stamps {@code finishedAt}; going back to
     * {@code queued} from {@code running} or {@code storing} is a restart: {@code attempt + 1}, progress and start cleared.
     *
     * @param code   the DRS code of a failure or cancellation, else null
     * @param reason one plain sentence, else null
     */
    public Run moveTo(RunState to, String code, String reason, Instant now) {
        state.check(to);
        boolean restart = to == RunState.QUEUED && (state == RunState.RUNNING || state == RunState.STORING);
        Instant started = to == RunState.RUNNING && startedAt == null ? now : restart ? null : startedAt;
        Instant finished = to.finished() && to != RunState.EXPIRED ? now : finishedAt;
        Double prog = restart ? null : to == RunState.DONE ? Double.valueOf(1.0) : progress;
        return new Run(id, runner, owner, workspace, sourceType, sourceId, sourceRef, question, params, asOf, dataVersion, fingerprint, signature,
                resultKey, tier, engine, to, prog, restart ? null : phase, estimate, rowsOut, bytesOut, code, reason, node, restart ? attempt + 1 : attempt,
                leaseUntil, submittedAt, started, finished, expiresAt, rerunOf, sharedFrom, groupId, promotedTo);
    }

    /** This run with measured progress and its phase in words ({@code building 12 of 41 dates}); only while running or storing. */
    public Run progress(Double value, String phaseWords) {
        if (state != RunState.RUNNING && state != RunState.STORING) {
            throw new IllegalStateException("progress moves only while running or storing, not " + state.wire());
        }
        return new Run(id, runner, owner, workspace, sourceType, sourceId, sourceRef, question, params, asOf, dataVersion, fingerprint, signature,
                resultKey, tier, engine, state, value, phaseWords, estimate, rowsOut, bytesOut, code, reason, node, attempt, leaseUntil, submittedAt,
                startedAt, finishedAt, expiresAt, rerunOf, sharedFrom, groupId, promotedTo);
    }

    /** This run with its result key and data version (set by the planner once the vector is read). */
    public Run keyed(ResultKey key) {
        return new Run(id, runner, owner, workspace, sourceType, sourceId, sourceRef, question, params, asOf, key.dataVersion(), fingerprint, signature,
                key.reusable() ? key.value() : null, tier, engine, state, progress, phase, estimate, rowsOut, bytesOut, code, reason, node, attempt,
                leaseUntil, submittedAt, startedAt, finishedAt, expiresAt, rerunOf, sharedFrom, groupId, promotedTo);
    }

    /** The node named in the id: where its row and result live (section 13). */
    public String ownerNode() {
        return RunId.node(id);
    }
}
