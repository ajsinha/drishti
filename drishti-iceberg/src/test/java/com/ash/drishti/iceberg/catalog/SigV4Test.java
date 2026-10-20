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
package com.ash.drishti.iceberg.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SigV4 for AWS Glue's Iceberg endpoint: the AWS SDK's signer, with Iceberg's property names, on the JDK client's request. */
class SigV4Test {

    @Test
    void aRequestIsSignedForTheServiceAndRegion() {
        SigV4 s = SigV4.from(Map.of("rest.access-key-id", "AKIDEXAMPLE", "rest.secret-access-key", "secret", "rest.signing-region", "eu-west-1",
                "rest.signing-name", "glue"));
        URI u = URI.create("https://glue.eu-west-1.amazonaws.com/iceberg/v1/config");
        HttpRequest.Builder b = HttpRequest.newBuilder(u).GET();
        byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);
        s.sign(b, "POST", u, body, Map.of());
        HttpRequest r = b.build();
        String auth = r.headers().firstValue("Authorization").orElseThrow();
        assertThat(auth).startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/").contains("/eu-west-1/glue/aws4_request", "SignedHeaders=", "Signature=");
        assertThat(r.headers().firstValue("X-Amz-Date")).isPresent();
        assertThat(r.headers().firstValue("x-amz-content-sha256").orElse(r.headers().firstValue("X-Amz-Content-Sha256").orElse(""))).hasSize(64);
        assertThat(r.headers().firstValue("Host")).isEmpty();                    // the JDK client sets it itself
    }
}
