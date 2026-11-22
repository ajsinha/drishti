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
package com.ash.drishti.api.lifecycle;

import java.time.Instant;

/**
 * One transition, as the audit, the event bus and the cluster digest carry it.
 *
 * @param type module, pack or connector
 * @param name its name
 * @param transition what was asked
 * @param from the state before
 * @param to the state after (equal to {@code from} when it was refused or threw)
 * @param actor who asked ({@code system} at start, {@code module <id>} when a module drives its parts)
 * @param at when it ended
 * @param outcome {@code done}, {@code refused} or {@code failed}
 * @param detail the words of a refusal or failure, else empty
 */
public record LifecycleEvent(Lifecycle.Type type, String name, Transition transition, LifecycleState from, LifecycleState to, String actor,
        Instant at, String outcome, String detail) {

    /** The audit action: {@code pack-suspend}, {@code connector-resume}, {@code module-shutdown}; a refusal adds {@code -refused}. */
    public String auditAction() {
        return type.word() + "-" + transition.word() + ("done".equals(outcome) ? "" : "-" + outcome);
    }

    /** The audit's target: {@code pack/market-risk}. */
    public String target() {
        return type.word() + "/" + name;
    }

    /** The audit's detail: {@code running -> suspended} and the words. */
    public String words() {
        return from.word() + " -> " + to.word() + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }
}
