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
package com.ash.drishti.server.node;

import java.util.List;

/**
 * Something readiness waits for (CD1, CLUSTER_GUIDE.md section 9.1): this node joining Drishti*net, an engine's newest date opening,
 * the shards this member owns becoming ready. Asked on every readiness probe, so it answers from memory and never waits; it may start work
 * in the background (opening a date) that makes it ready later.
 */
public interface ReadinessGate {

    /** A short name for the status: {@code net}, {@code var}, {@code var-shards}, {@code exposure}. */
    String name();

    /** Which switch of {@code drishti.node.readiness} turns this gate off: {@code joining}, {@code engines} or {@code shards}. */
    String kind();

    /** What this node still waits for, one line each; empty when ready. */
    List<String> waitingFor();
}
