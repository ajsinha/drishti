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
import com.ash.drishti.testkit.s3.S3MockServer;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/** The S3 backend's contract against Adobe S3Mock in this JVM (no Docker, no binary: runs on Windows too). */
class S3MockContentStoreTest extends S3ContentStoreCases {

    private static S3MockServer s3;

    @BeforeAll
    static void up() throws Exception {
        s3 = S3MockServer.start(Path.of("target", "s3mock-content"), "content");
        probe(s3.endpoint(), "content");
    }

    @AfterAll
    static void down() {
        s3.close();
    }

    @Override
    protected S3Endpoint endpoint() {
        return s3.endpoint();
    }
}
