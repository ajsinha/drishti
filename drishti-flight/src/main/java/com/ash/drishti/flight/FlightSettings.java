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

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The Arrow Flight service's settings: {@code drishti.engines.flight.*} in {@code config/engines/flight.yaml} (docs/guides/FLIGHT_GUIDE.md,
 * "Turning it on"). TLS is always on: the certificate chain and its private key are PEM files (the server's certificate); a client CA turns
 * mutual TLS on. {@code advertiseHost} is the address clients are given in {@code GetFlightInfo}: the node's real address, never a loopback
 * or wildcard address (empty: the node's Drishti*net address).
 *
 * @param enabled         the engine switch (off by default)
 * @param bindHost        the address the port is opened on ({@code 0.0.0.0}: every interface)
 * @param port            the Flight port (default 18815)
 * @param advertiseHost   the address put in the endpoints; empty: the node's own real address
 * @param certChain       the PEM certificate chain the port presents
 * @param privateKey      its PEM private key (PKCS#8)
 * @param clientCa        PEM CA bundle that client certificates must chain to; null: no client certificate asked (tokens only)
 * @param totalMemory     the cap on Arrow memory of every Flight stream of the node together, bytes
 * @param requestMemory   the cap on one stream's Arrow memory, bytes (a role's limit may be lower)
 * @param batchRows       the rows of one record batch sent
 * @param sessionTtl      how long a session issued at the handshake lasts
 * @param streamKeep      how many batches of a live stream are kept for resuming
 * @param readersKept     how long finished requests are listed on Admin → Data out
 * @param defaultLimits   the limits of a role not named in {@code roleLimits}
 * @param roleLimits      limits per role (the token's most generous role applies)
 */
public record FlightSettings(boolean enabled, String bindHost, int port, String advertiseHost, Path certChain, Path privateKey, Path clientCa,
        long totalMemory, long requestMemory, int batchRows, Duration sessionTtl, int streamKeep, Duration readersKept,
        FlightLimits defaultLimits, Map<String, FlightLimits> roleLimits) {

    public static final int DEFAULT_PORT = 18815;

    public FlightSettings {
        Objects.requireNonNull(bindHost, "bindHost");
        advertiseHost = advertiseHost == null ? "" : advertiseHost.trim();
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("drishti.engines.flight.port must be 1..65535, not " + port);
        }
        if (batchRows < 1) {
            throw new IllegalArgumentException("drishti.engines.flight.batch-rows must be at least 1");
        }
        if (totalMemory <= 0 || requestMemory <= 0 || requestMemory > totalMemory) {
            throw new IllegalArgumentException("drishti.engines.flight memory caps: 0 < request-memory <= total-memory");
        }
        sessionTtl = sessionTtl == null ? Duration.ofHours(1) : sessionTtl;
        readersKept = readersKept == null ? Duration.ofDays(1) : readersKept;
        streamKeep = Math.max(1, streamKeep);
        defaultLimits = defaultLimits == null ? FlightLimits.DEFAULT : defaultLimits;
        roleLimits = roleLimits == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(roleLimits));
    }

    /** The shipped defaults with TLS from these files (tests and the engine file's defaults). */
    public static FlightSettings defaults(Path certChain, Path privateKey) {
        return new FlightSettings(false, "0.0.0.0", DEFAULT_PORT, "", certChain, privateKey, null, 4L << 30, 1L << 30, 65_536,
                Duration.ofHours(1), 1_024, Duration.ofDays(1), FlightLimits.DEFAULT, Map.of());
    }

    /** A copy on another port and bind address (tests use a free port on loopback). */
    public FlightSettings on(String host, int newPort) {
        return new FlightSettings(enabled, host, newPort, advertiseHost.isEmpty() ? host : advertiseHost, certChain, privateKey, clientCa,
                totalMemory, requestMemory, batchRows, sessionTtl, streamKeep, readersKept, defaultLimits, roleLimits);
    }

    /** A copy with these limits per role. */
    public FlightSettings withLimits(FlightLimits fallback, Map<String, FlightLimits> perRole) {
        return new FlightSettings(enabled, bindHost, port, advertiseHost, certChain, privateKey, clientCa, totalMemory, requestMemory,
                batchRows, sessionTtl, streamKeep, readersKept, fallback, perRole);
    }

    /** A copy with these memory caps and batch size. */
    public FlightSettings withMemory(long total, long perRequest, int rowsPerBatch) {
        return new FlightSettings(enabled, bindHost, port, advertiseHost, certChain, privateKey, clientCa, total, perRequest, rowsPerBatch,
                sessionTtl, streamKeep, readersKept, defaultLimits, roleLimits);
    }

    /** The limits for a caller: the most generous of its roles' limits, or the default; the memory never above {@link #requestMemory}. */
    public FlightLimits limitsFor(Iterable<String> roles) {
        FlightLimits best = null;
        for (String r : roles) {
            FlightLimits l = roleLimits.get(r);
            if (l != null) {
                best = best == null ? l : best.max(l);
            }
        }
        FlightLimits l = best == null ? defaultLimits : best;
        return l.memory() > requestMemory ? l.withMemory(requestMemory) : l;
    }
}
