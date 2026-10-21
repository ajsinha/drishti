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

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** The S3 backend: the contract, against an in-process S3 stand-in that honours the conditional headers (MinIO: {@link MinioContentStoreTest}). */
class S3ContentStoreTest extends ContentStoreContract {

    private static FakeS3 s3;
    private String prefix;

    @BeforeAll
    static void up() throws Exception {
        s3 = new FakeS3();
    }

    @AfterAll
    static void down() {
        s3.close();
    }

    @Override
    protected ContentStore open() {
        prefix = "t-" + UUID.randomUUID();
        return reopen();
    }

    @Override
    protected ContentStore reopen() {
        return new S3ContentStore(new S3ContentStore.Settings("bucket", prefix, "us-east-1", s3.endpoint(), true, "key", "secret"));
    }
}
