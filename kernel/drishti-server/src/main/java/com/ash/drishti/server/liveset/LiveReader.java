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
package com.ash.drishti.server.liveset;

import com.ash.drishti.api.DataNode;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * One reader of the live sets, made for each request from the person and never kept beyond it and the reader's own stream
 * (modules/trading/docs/guide.md section 7, "Who sees what"): what decides which rows they may see and how.
 *
 * @param audience everything that decides what the reader may see, as text: two readers with equal audiences see the same rows the
 *     same way (their roles, the embedding app, their masks, their row scope, whether they see the cluster's nodes). A search's set is
 *     filled for one audience and read only by it; a declared set's rows are shared and every read is filtered by the reader's scope
 *     and masked by their masks
 * @param redact the reader's field masks
 * @param scope the reader's row scope on the kind
 * @param admin whether the reader may see which node of the cluster answered what
 */
public record LiveReader(String audience, UnaryOperator<DataNode> redact, Scope scope, boolean admin) {

    public LiveReader {
        audience = audience == null ? "" : audience;
        redact = redact == null ? UnaryOperator.identity() : redact;
        scope = scope == null ? Scope.ALL : scope;
    }

    /**
     * A reader's row scope: a test of a record as the store holds it (before masks), the fields it reads (a set paged from the store
     * reads them as columns) and its text (part of the audience).
     */
    public record Scope(String signature, Set<String> fields, Predicate<DataNode> test) {

        /** Every record of a kind the reader may open. */
        public static final Scope ALL = new Scope("", Set.of(), d -> true);

        public Scope {
            signature = signature == null ? "" : signature;
            fields = fields == null ? Set.of() : Set.copyOf(fields);
            test = test == null ? d -> true : test;
        }

        public boolean all() {
            return this == ALL || (signature.isEmpty() && fields.isEmpty());
        }
    }

    /** Whoever may see everything as stored (tests; a server without security). */
    public static LiveReader everyone() {
        return new LiveReader("", null, Scope.ALL, true);
    }

    /** True when the reader sees every value as stored. */
    public boolean unmasked() {
        return redact == UnaryOperator.<DataNode>identity();
    }
}
