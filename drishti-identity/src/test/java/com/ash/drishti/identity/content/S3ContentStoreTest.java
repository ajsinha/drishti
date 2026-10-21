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

import com.ash.drishti.testkit.s3.S3Endpoint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The S3 backend: the contract, against an in-process S3 stand-in that honours the conditional headers, so it covers every case
 * a simulator skips (S3Mock: {@link S3MockContentStoreTest}; MinIO: {@link MinioContentStoreTest}).
 */
class S3ContentStoreTest extends S3ContentStoreCases {

    private static FakeS3 s3;
    private static S3Endpoint endpoint;

    @BeforeAll
    static void up() throws Exception {
        s3 = new FakeS3();
        endpoint = new S3Endpoint("in-process stand-in (FakeS3)", s3.endpoint(), "key", "secret");
        probe(endpoint, "content");
    }

    @AfterAll
    static void down() {
        s3.close();
    }

    @Override
    protected S3Endpoint endpoint() {
        return endpoint;
    }
}
