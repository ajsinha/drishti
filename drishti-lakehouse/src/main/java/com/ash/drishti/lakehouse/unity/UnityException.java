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
package com.ash.drishti.lakehouse.unity;

import java.io.IOException;

/** Unity Catalog refused, in words: why ({@link Reason}) and what to do. */
public final class UnityException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Why Unity Catalog refused. */
    public enum Reason {
        /** The table (or its schema or catalog) does not exist, or is not visible to the principal. */
        NOT_FOUND,
        /** The principal lacks a grant on the table. */
        NO_GRANT,
        /** Credential vending is not allowed (the metastore's external data access, or EXTERNAL USE SCHEMA). */
        VENDING_OFF,
        /** The token or OAuth client was refused. */
        SIGN_IN,
        /** The table is not one this connector reads (a view, another format, a store the engine does not reach). */
        NOT_READABLE,
        /** Anything else. */
        OTHER
    }

    private final Reason reason;

    public UnityException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
