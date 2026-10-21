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
package com.ash.drishti.plugin.iceberg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ash.drishti.testkit.s3.ConditionalWrites;
import com.ash.drishti.testkit.s3.MinioServer;
import com.ash.drishti.testkit.s3.S3Endpoint;
import com.ash.drishti.testkit.s3.S3MockServer;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@link NativeOnS3Test}'s table on S3 without Docker: Adobe S3Mock in this JVM (runs on Windows too) and the MinIO server binary
 * (Linux only, skipped with the reason elsewhere or when it cannot be had). Writers committing at once run only where racing
 * conditional creates are atomic, which the probe finds and the report line says.
 */
class NativeOnS3SimulatorTest {

    @Test
    void onS3MockInProcessTheLoaderWritesAndTheConnectorReadsATable() throws Exception {
        try (S3MockServer s3 = S3MockServer.start(Path.of("target", "s3mock-iceberg"), "lake")) {
            run(s3.endpoint());
        }
    }

    @Test
    void onTheMinioBinaryTheTableAndWritersAtOnce() throws Exception {
        try (MinioServer minio = MinioServer.startOrSkip(Path.of("target", "minio-iceberg", "data"))) {
            minio.endpoint().bucket("lake");
            run(minio.endpoint());
        }
    }

    private static void run(S3Endpoint e) throws Exception {
        ConditionalWrites w = ConditionalWrites.probe(e, "lake");
        System.out.println(w.report());
        assumeTrue(w.createOnly(), w.notHonoured("If-None-Match: * (the commit's create-only PUT)"));
        assertThat(NativeOnS3Test.loadReadAndCommitAtOnce(NativeOnS3Test.settings(e.endpoint(), e.accessKey(), e.secretKey()), w.atomic()))
                .as(e.simulator() + " honours If-None-Match").isTrue();
    }
}
