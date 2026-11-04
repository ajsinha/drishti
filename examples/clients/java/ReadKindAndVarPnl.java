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

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStatusCode;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;

/**
 * A read-only Arrow Flight client in one file (examples/clients/java/README.md): connect with an API token, read a kind's rows, then a
 * desk's VaR scenario P&L with the engine's VaR and ES from the schema's metadata.
 *
 * <pre>
 * export DRISHTI_FLIGHT_URL=grpc+tls://&lt;a node's real address&gt;:18815
 * export DRISHTI_TOKEN_FILE=~/.config/drishti/token      # or DRISHTI_TOKEN; a token with the scope data:read
 * export DRISHTI_FLIGHT_CA=ca.pem
 * java --add-opens=java.base/java.nio=ALL-UNNAMED -cp "lib/*" ReadKindAndVarPnl.java [DESK:RATES]
 * </pre>
 *
 * It only reads: Flight refuses every call that would write, and none is made. Each part of a plan is read from the first of its nodes
 * that answers, the next one when a node is down.
 */
public final class ReadKindAndVarPnl {

    private final BufferAllocator allocator = new RootAllocator();
    private final byte[] caPem;
    private final String token;
    private final Map<String, FlightClient> clients = new HashMap<>();
    private final Map<String, CallOption> sessions = new HashMap<>();

    ReadKindAndVarPnl(String token, byte[] caPem) {
        this.token = token;
        this.caPem = caPem;
    }

    public static void main(String[] args) throws Exception {
        String url = env("DRISHTI_FLIGHT_URL", null);
        if (url == null || !url.startsWith("grpc+tls://")) {
            System.err.println("Set DRISHTI_FLIGHT_URL to grpc+tls://<a node's real address>:18815");
            System.exit(2);
        }
        String ca = env("DRISHTI_FLIGHT_CA", null);
        String scope = args.length > 0 ? args[0] : env("DRISHTI_EXAMPLE_SCOPE", "DESK:RATES");
        ReadKindAndVarPnl reader = new ReadKindAndVarPnl(token(), ca == null ? null : Files.readAllBytes(Path.of(ca)));
        try {
            reader.trades(url);
            reader.varPnl(url, scope);
        } catch (FlightRuntimeException e) {
            System.err.println(e.getMessage());               // DRS-56xx: the words say what to do
            System.exit(2);
        } finally {
            reader.close();
        }
    }

    /** A kind's rows: the trades of the latest business date, five paths, the first rows printed. */
    void trades(String url) throws Exception {
        String request = """
            {"form": "kind", "request": {"kind": "trade", "paths": ["TRADE_ID", "SOURCE_SYSTEM", "PRODUCT_TYPE", "CURRENCY", "NOTIONAL"]}}""";
        long rows = 0;
        FlightInfo info = plan(url, request);
        for (FlightEndpoint endpoint : info.getEndpoints()) {
            try (FlightStream stream = read(url, endpoint)) {
                while (stream.next()) {
                    VectorSchemaRoot root = stream.getRoot();
                    if (rows == 0) {
                        System.out.println("trade: " + root.getSchema());
                        System.out.print(root.contentToTSVString().lines().limit(6).reduce("", (a, b) -> a + b + "\n"));
                    }
                    rows += root.getRowCount();
                }
            }
        }
        System.out.println(rows + " trades");
    }

    /** A scope's VaR scenario P&L (float64, the engine's sum) and the engine's VaR and ES from the schema's metadata. */
    void varPnl(String url, String scope) throws Exception {
        String request = """
            {"form": "measure", "request": {"measure": "var.scenarios", "scope": "%s"}}""".formatted(scope.replace("\"", ""));
        FlightInfo info = plan(url, request);
        Map<String, String> meta = info.getSchemaOptional().map(s -> s.getCustomMetadata()).orElse(Map.of());
        int scenarios = 0;
        double worst = Double.POSITIVE_INFINITY;
        String worstDate = "";
        for (FlightEndpoint endpoint : info.getEndpoints()) {
            try (FlightStream stream = read(url, endpoint)) {
                while (stream.next()) {
                    VectorSchemaRoot root = stream.getRoot();
                    Float8Vector pnl = (Float8Vector) root.getVector("PNL");
                    FieldVector dates = root.getVector("SCENARIO_DATE");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        if (!pnl.isNull(i) && pnl.get(i) < worst) {
                            worst = pnl.get(i);
                            worstDate = String.valueOf(dates.getObject(i));
                        }
                    }
                    scenarios += root.getRowCount();
                }
            }
        }
        System.out.printf("%s on %s: %d scenarios, worst P&L %,.0f on %s%n", meta.getOrDefault("drishti.scope", scope),
                meta.getOrDefault("drishti.date", "?"), scenarios, worst, worstDate);
        System.out.printf("VaR %s  ES %s  %s (the engine's figures)%n", meta.get("drishti.var"), meta.get("drishti.es"),
                meta.getOrDefault("drishti.currency", ""));
    }

    // ---- the plumbing: a client per node, the token at the handshake, a part from the first node that answers ----------------

    private FlightInfo plan(String url, String request) {
        return client(url).getInfo(FlightDescriptor.command(request.getBytes(StandardCharsets.UTF_8)), sessions.get(url));
    }

    private FlightStream read(String url, FlightEndpoint endpoint) {
        List<String> nodes = new ArrayList<>();
        for (Location location : endpoint.getLocations()) {
            nodes.add(location.getUri().toString());
        }
        if (nodes.isEmpty()) {
            nodes.add(url);
        }
        FlightRuntimeException last = null;
        for (String node : nodes) {
            try {
                FlightStream stream = client(node).getStream(endpoint.getTicket(), sessions.get(node));
                stream.getRoot();                              // waits for the schema: a node that is down fails here
                return stream;
            } catch (FlightRuntimeException e) {
                if (e.status().code() != FlightStatusCode.UNAVAILABLE) {
                    throw e;                                   // the node answered and refused: not a node down
                }
                last = e;
                forget(node);
            }
        }
        throw CallStatus.UNAVAILABLE.withDescription("no node of this part answered: " + nodes + " (" + last + ")").toRuntimeException();
    }

    private FlightClient client(String url) {
        return clients.computeIfAbsent(url, u -> {
            URI uri = URI.create(u);
            FlightClient.Builder builder = FlightClient.builder(allocator, Location.forGrpcTls(uri.getHost(), uri.getPort()));
            if (caPem != null) {
                builder.trustedCertificates(new java.io.ByteArrayInputStream(caPem));
            }
            FlightClient c = builder.build();
            CallOption session = c.authenticateBasicToken("token", token)
                    .orElseThrow(() -> new IllegalStateException("the handshake gave no session"));
            sessions.put(u, session);
            return c;
        });
    }

    private void forget(String url) {
        FlightClient c = clients.remove(url);
        sessions.remove(url);
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // a node that is down may fail to close too
            }
        }
    }

    void close() throws Exception {
        for (FlightClient c : clients.values()) {
            c.close();
        }
        allocator.close();
    }

    private static String token() throws Exception {
        String value = env("DRISHTI_TOKEN", null);
        if (value != null) {
            return value;
        }
        String file = env("DRISHTI_TOKEN_FILE", null);
        if (file != null) {
            try (InputStream in = Files.newInputStream(Path.of(file.replaceFirst("^~", System.getProperty("user.home"))))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().findFirst().orElse("").strip();
            }
        }
        System.err.println("Set DRISHTI_TOKEN (or DRISHTI_TOKEN_FILE) to an API token with the scope data:read");
        System.exit(2);
        return null;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v.strip();
    }
}
