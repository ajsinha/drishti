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

import com.ash.drishti.testkit.s3.MinioServer;
import com.ash.drishti.testkit.s3.S3Endpoint;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * The S3 backend's contract against the real MinIO server binary: Linux only, and skipped with the reason on Windows, offline,
 * or when no binary can be had (see {@link MinioServer}).
 */
class MinioContentStoreTest extends S3ContentStoreCases {

    private static MinioServer minio;

    @BeforeAll
    static void up() throws Exception {
        minio = MinioServer.startOrSkip(Path.of("target", "minio-content", "data"));
        minio.endpoint().bucket("content");
        probe(minio.endpoint(), "content");
    }

    @AfterAll
    static void down() {
        if (minio != null) {
            minio.close();
        }
    }

    @Override
    protected S3Endpoint endpoint() {
        return minio.endpoint();
    }
}
