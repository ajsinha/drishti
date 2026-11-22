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
package com.ash.drishti.enginehost;

import java.util.Set;

/**
 * The caller as the host's access rules see them (EN-47: the host decides and enforces, on inputs and on outputs). Built per request from
 * the server's entitlements; never kept by an engine and never shared between callers.
 */
public interface ViewerAccess {

    /** The person or the service the request runs as: for the audit line only. */
    String actor();

    /** The roles held ({@code access.opens: [role:<name>]}). */
    Set<String> roles();

    /** True for an administrator. */
    boolean admin();

    /** Whether the caller may open (read) the kind at all; a kind they may not open is not present for them. */
    boolean mayOpen(String kind);

    /** The caller's row rule on the kind; {@link RowRule#ALL} when they see every row. */
    RowRule rows(String kind);

    /** Whether the caller may open the pack's kinds ({@code access.opens: [pack:<name>]}). */
    default boolean mayOpenPack(String pack) {
        return false;
    }

    /** Whether the caller sees the field masked. */
    boolean masked(String field);

    /** A system identity that sees every row (a schedule's {@code as: system}). */
    static ViewerAccess system(String name) {
        return new ViewerAccess() {
            @Override public String actor() { return name; }
            @Override public Set<String> roles() { return Set.of(); }
            @Override public boolean admin() { return true; }
            @Override public boolean mayOpen(String kind) { return true; }
            @Override public RowRule rows(String kind) { return RowRule.ALL; }
            @Override public boolean masked(String field) { return false; }
        };
    }
}
