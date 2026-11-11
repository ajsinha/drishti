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
package com.ash.drishti.testkit.s3;

import com.adobe.testing.s3mock.S3MockApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Adobe S3Mock in this JVM (a Maven test dependency: no Docker, no native binary, so it runs on the Windows QA machine too).
 * Its files go under {@code root} (a folder under {@code target/}). A test that uses it declares
 * {@code com.adobe.testing:s3mock} and {@code software.amazon.awssdk:s3} itself (they are optional here).
 */
public final class S3MockServer implements AutoCloseable {

    /** The simulator's name in report lines. */
    public static final String NAME = "S3Mock 4.12.4 (in-process)";

    private final S3MockApplication app;
    private final S3Endpoint endpoint;

    private S3MockServer(S3MockApplication app, int port) {
        this.app = app;
        this.endpoint = new S3Endpoint(NAME, "http://127.0.0.1:" + port, "key", "secret");
    }

    /** Starts S3Mock on a free port in 18750-18759 with these buckets, its files under {@code root}. */
    public static S3MockServer start(Path root, String... buckets) throws Exception {
        Files.createDirectories(root);
        // hadoop-client-runtime registers its shaded (older) Woodstox as the StAX provider, which S3Mock's XML converter rejects:
        // name S3Mock's own Woodstox unless the test already chose one
        System.getProperties().putIfAbsent("javax.xml.stream.XMLOutputFactory", "com.ctc.wstx.stax.WstxOutputFactory");
        System.getProperties().putIfAbsent("javax.xml.stream.XMLInputFactory", "com.ctc.wstx.stax.WstxInputFactory");
        int http = S3Endpoint.freePort();
        int https = S3Endpoint.freePort();
        Map<String, Object> p = new HashMap<>();
        p.put(S3MockApplication.PROP_HTTP_PORT, http);
        p.put(S3MockApplication.PROP_HTTPS_PORT, https);
        p.put(S3MockApplication.PROP_ROOT_DIRECTORY, root.toAbsolutePath().toString());
        p.put(S3MockApplication.PROP_INITIAL_BUCKETS, String.join(",", buckets));
        p.put(S3MockApplication.PROP_SILENT, true);
        p.put("server.address", "127.0.0.1");
        p.put("spring.main.banner-mode", "off");
        // the test classpath may hold JPA, a JDBC driver or Drishti's own Spring configuration: S3Mock needs none of it
        p.put("spring.autoconfigure.exclude", String.join(",",
                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
                "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration"));
        return new S3MockServer(S3MockApplication.start(p), http);
    }

    /** Where it listens and how to sign in. */
    public S3Endpoint endpoint() {
        return endpoint;
    }

    @Override
    public void close() {
        app.stop();
    }
}
