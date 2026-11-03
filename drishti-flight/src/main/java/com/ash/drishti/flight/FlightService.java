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
package com.ash.drishti.flight;

import com.ash.drishti.compute.arrow.ArrowMemory;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.arrow.flight.FlightServer;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Arrow Flight service of one node: a gRPC port (default 18815) with TLS always, the token check at the handshake and Drishti's
 * producer. A plain Java service: the server starts it when the engine {@code flight} is on (config/engines/flight.yaml) and stops it with
 * the node. Its Arrow memory is a capped child of the process's root ({@link ArrowMemory}), so busy protection counts it.
 */
public final class FlightService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FlightService.class);

    private final FlightSettings settings;
    private final FlightBackend backend;
    private final Clock clock;
    private final FlightActivity activity;
    private final TokenAuthenticator auth;
    private BufferAllocator allocator;
    private ExecutorService executor;
    private FlightServer server;

    public FlightService(FlightSettings settings, FlightBackend backend, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.activity = new FlightActivity(settings.readersKept(), this.clock);
        this.auth = new TokenAuthenticator(backend, settings.sessionTtl(), this.clock);
    }

    /** Opens the port. TLS is always on: without a certificate and its key the service does not start, and says why. */
    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        if (settings.certChain() == null || settings.privateKey() == null || !Files.isReadable(settings.certChain())
                || !Files.isReadable(settings.privateKey())) {
            throw new IOException("Arrow Flight needs TLS: drishti.engines.flight.tls.certificate and .private-key must name readable PEM "
                    + "files (the server's certificate); see docs/guides/FLIGHT_GUIDE.md, Turning it on");
        }
        allocator = ArrowMemory.child("flight", settings.totalMemory());
        executor = Executors.newVirtualThreadPerTaskExecutor();
        DrishtiFlightProducer producer = new DrishtiFlightProducer(backend, settings, auth, activity, allocator, clock);
        FlightServer.Builder b = FlightServer.builder(allocator, Location.forGrpcTls(settings.bindHost(), settings.port()), producer)
                .useTls(settings.certChain().toFile(), settings.privateKey().toFile())
                .headerAuthenticator(auth)
                .executor(executor);
        if (settings.clientCa() != null) {
            b.useMTlsClientVerification(settings.clientCa().toFile());
        }
        FlightServer s = b.build();
        try {
            s.start();
        } catch (IOException | RuntimeException e) {
            close(s);
            allocator.close();
            executor.shutdownNow();
            throw e;
        }
        server = s;
        log.info("Arrow Flight on {}:{} (TLS{}), Arrow memory cap {} bytes", settings.bindHost(), server.getPort(),
                settings.clientCa() == null ? "" : ", client certificates", settings.totalMemory());
    }

    /** The port in use (the configured one, or the one chosen when it was 0). */
    public synchronized int port() {
        return server == null ? settings.port() : server.getPort();
    }

    public synchronized boolean running() {
        return server != null;
    }

    public FlightActivity activity() {
        return activity;
    }

    public TokenAuthenticator authenticator() {
        return auth;
    }

    public FlightSettings settings() {
        return settings;
    }

    /** Arrow bytes held by Flight now, and its cap. */
    public synchronized long[] memory() {
        return allocator == null ? new long[] {0, settings.totalMemory()} : new long[] {allocator.getAllocatedMemory(), allocator.getLimit()};
    }

    @Override
    public synchronized void close() {
        if (server == null) {
            return;
        }
        close(server);
        server = null;
        executor.shutdownNow();
        try {
            allocator.close();
        } catch (IllegalStateException e) {
            log.warn("Arrow Flight closed with memory still held: {}", e.getMessage());
        }
    }

    private static void close(FlightServer s) {
        try {
            s.shutdown();
            if (!s.awaitTermination(5, TimeUnit.SECONDS)) {
                s.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("Flight server close: {}", e.toString());
        }
    }
}
