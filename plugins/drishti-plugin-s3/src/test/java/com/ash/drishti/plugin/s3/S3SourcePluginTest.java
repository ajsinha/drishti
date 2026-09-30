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
package com.ash.drishti.plugin.s3;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.testkit.DatedSourceContract;
import java.net.URI;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** The S3 connector against an S3-compatible server (Adobe S3Mock) in Docker. Skipped where Docker is not reachable. */
@Testcontainers(disabledWithoutDocker = true)
class S3SourcePluginTest {

    @Container
    static final GenericContainer<?> S3 = new GenericContainer<>(
            "adobe/s3mock@sha256:ab01a6946750f451ca215a47e91030695b260e4003b8a5a6201d25029b8fca92")
            .withExposedPorts(9090).waitingFor(Wait.forHttp("/").forPort(9090).forStatusCodeMatching(c -> c < 500));

    static String endpoint() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(9090);
    }

    @BeforeAll
    static void load() {
        try (S3Client c = S3Client.builder().endpointOverride(URI.create(endpoint())).forcePathStyle(true).region(Region.US_EAST_1)
                .httpClient(UrlConnectionHttpClient.create())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("k", "s"))).build()) {
            c.createBucket(b -> b.bucket("lake"));
            put(c, "risk/trade/T-1.json", "{\"tradeId\":\"T-1\",\"mtm\":100}");
            put(c, "risk/2026-09-28/trade/T-1.json", "{\"tradeId\":\"T-1\",\"mtm\":98}");
            put(c, "risk/2026-09-29/trade/T-1.json", "{\"tradeId\":\"T-1\",\"mtm\":99}");
            put(c, "risk/2026-09-29/trade/T-2.json", "{\"tradeId\":\"T-2\",\"mtm\":7}");
            put(c, "risk/counterparty/CP-A.json", "{\"name\":\"Acme\"}");
            put(c, "risk/notes.txt", "not an entity");
            put(c, "other/trade/T-9.json", "{\"tradeId\":\"T-9\"}");            // outside the prefix
        }
    }

    private static void put(S3Client c, String key, String body) {
        c.putObject(b -> b.bucket("lake").key(key), RequestBody.fromString(body));
    }

    private static S3SourcePlugin plugin(String endpoint) {
        S3SourcePlugin p = new S3SourcePlugin();
        p.start(DatedSourceContract.context(Map.of("bucket", "lake", "prefix", "risk", "endpoint", endpoint, "access-key", "k", "secret-key", "s",
                "source-name", "risk-s3", "cache-seconds", "1")));
        return p;
    }

    private static double mtm(S3SourcePlugin p, String id, LocalDate d) throws Exception {
        return p.fetch(EntityRef.of("trade", id), d == null ? AsOf.LATEST : new AsOf(d, null)).orElseThrow().data().get("mtm").asDouble();
    }

    @Test
    void readsUndatedAndDatedDocumentsFindsThemAndStaysInsideItsPrefix() throws Exception {
        S3SourcePlugin p = plugin(endpoint());
        try {
            assertThat(p.health()).isEqualTo("UP");
            assertThat(p.manifest().kinds()).containsExactlyInAnyOrder("trade", "counterparty");
            assertThat(mtm(p, "T-1", LocalDate.of(2026, 9, 29))).isEqualTo(99);
            assertThat(mtm(p, "T-1", LocalDate.of(2026, 9, 28))).isEqualTo(98);
            assertThat(mtm(p, "T-1", LocalDate.of(2026, 9, 30))).isEqualTo(99);                 // newest on or before
            assertThat(p.fetch(EntityRef.of("trade", "T-1"), new AsOf(LocalDate.of(2026, 9, 29), null)).orElseThrow().provenance().businessDate())
                    .isEqualTo(LocalDate.of(2026, 9, 29));
            assertThat(p.fetch(EntityRef.of("trade", "T-2"), new AsOf(LocalDate.of(2026, 9, 28), null))).isEmpty();   // not yet there
            assertThat(p.fetch(EntityRef.of("counterparty", "CP-A")).orElseThrow().data().get("name").asText()).isEqualTo("Acme");
            assertThat(p.fetch(EntityRef.of("trade", "T-9"))).isEmpty();
            assertThat(p.fetch(EntityRef.of("trade", "../other/trade/T-9"))).isEmpty();
            assertThat(p.search("trade", "t-", 10)).extracting(h -> h.ref().id()).containsExactlyInAnyOrder("T-1", "T-2");
        } finally {
            p.close();
        }
    }

    @Test
    void anUnreachableStoreShowsInHealthAndTheConnectorStillStarts() {
        S3SourcePlugin p = plugin("http://127.0.0.1:1");
        try {
            assertThat(p.health()).startsWith("DOWN");
        } finally {
            p.close();
        }
    }
}
