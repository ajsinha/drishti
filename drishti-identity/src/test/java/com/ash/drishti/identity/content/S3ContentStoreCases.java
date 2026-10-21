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
package com.ash.drishti.identity.content;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.testkit.s3.ConditionalWrites;
import com.ash.drishti.testkit.s3.S3Endpoint;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The content store contract on an S3 API simulator: the simulator's conditional writes are probed first (and the report line
 * printed), and a case that needs one the simulator does not honour is skipped with the reason; the in-process stand-in
 * ({@link S3ContentStoreTest}) honours all three, so every case stays covered.
 */
abstract class S3ContentStoreCases extends ContentStoreContract {

    protected static ConditionalWrites writes;
    private String prefix;

    /** Where the simulator listens. */
    protected abstract S3Endpoint endpoint();

    static void probe(S3Endpoint e, String bucket) {
        writes = ConditionalWrites.probe(e, bucket);
        System.out.println(writes.report());
    }

    @Override
    protected ContentStore open() {
        prefix = "t-" + UUID.randomUUID();
        return reopen();
    }

    @Override
    protected ContentStore reopen() {
        S3Endpoint e = endpoint();
        return new S3ContentStore(new S3ContentStore.Settings("content", prefix, "us-east-1", e.endpoint(), true, e.accessKey(), e.secretKey()));
    }

    @Test
    @Override
    void conflictsAnswerDrs5057() {
        assumeTrue(writes.createOnly(), writes.notHonoured("If-None-Match: * (create-only)"));
        assumeTrue(writes.updateIfMatch(), writes.notHonoured("If-Match on a put (update-if-match)"));
        super.conflictsAnswerDrs5057();
    }

    @Test
    @Override
    void concurrentCreatesAndUpdatesHaveExactlyOneWinner() throws Exception {
        assumeTrue(writes.createOnly() && writes.updateIfMatch(), writes.notHonoured("create-only and update-if-match"));
        assumeTrue(writes.atomic(), writes.notHonoured("conditional writes atomically under a race"));
        super.concurrentCreatesAndUpdatesHaveExactlyOneWinner();
    }

    @Test
    @Override
    void deleteIsCompareAndSet() {
        assumeTrue(writes.deleteIfMatch(), writes.notHonoured("If-Match on a delete (delete-if-match)"));
        super.deleteIsCompareAndSet();
    }
}
