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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.compute.arrow.ArrowMemory;
import com.ash.drishti.compute.arrow.ArrowTable;
import com.ash.drishti.testkit.TlsFixture;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.Criteria;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.Result;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Flight service end to end on loopback with TLS (a throw-away PKI): the token at the handshake and the session after it, a plan's
 * endpoints, a snapshot in batches, the limits and their codes, a live stream with its sequences and resume, an administrator's stop, the
 * audit of every read, and no Arrow memory left behind.
 */
class FlightServiceTest {

    static final String TOKEN = "drk_test_token";

    TlsFixture tls;
    Fake backend;
    FlightService service;
    BufferAllocator client;

    /** A backend with one table of 10 rows (ID, VALUE) and a live stream of three batches. */
    static final class Fake implements FlightBackend {
        final List<Audit> audits = new CopyOnWriteArrayList<>();
        final AtomicLong admitted = new AtomicLong();
        volatile boolean busy;
        final int rows;

        Fake(int rows) {
            this.rows = rows;
        }

        ArrowTable table() {
            long[] id = new long[rows];
            double[] v = new double[rows];
            for (int i = 0; i < rows; i++) {
                id[i] = i;
                v[i] = i * 1.5;
            }
            return ArrowTable.builder().longs("ID", id).doubles("VALUE", v).build();
        }

        @Override
        public Caller authenticate(String token, String onBehalfOf) {
            if (!TOKEN.equals(token)) {
                throw new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "the API token is unknown, revoked or expired");
            }
            return new Caller("app", onBehalfOf, "t1", Set.of("risk-viewer"), Set.of("data:read"));
        }

        @Override
        public Plan plan(Caller caller, FlightRequest request) {
            if (request.request().path("kind").asText().equals("secret")) {
                throw new FlightRefusal(ErrorCode.FLIGHT_SCOPE, "this token lacks data:read:secret");
            }
            return new Plan(new TableBatches(table(), Map.of(), 4).schema(),
                    List.of(new Part("p0", List.of(new Node("n1", "127.0.0.1", 18815), new Node("n2", "10.0.0.2", 18815)), rows)), rows, -1);
        }

        @Override
        public BatchSource open(Caller caller, FlightRequest request, int part) {
            return new TableBatches(table(), Map.of("drishti.kind", "trade"), 4);
        }

        @Override
        public Subscription subscribe(Caller caller, FlightRequest request, int part) {
            long[] seq = {Math.max(0, request.resume())};
            Schema schema = new TableBatches(table(), Map.of(), 4).schema();
            return new Subscription() {
                @Override
                public Schema schema() {
                    return schema;
                }

                @Override
                public long next(VectorSchemaRoot root, Duration wait) {
                    if (seq[0] >= 3) {
                        return END;
                    }
                    seq[0]++;
                    com.ash.drishti.compute.arrow.ArrowIpc.fill(root, table(), 0, 1);
                    return seq[0];
                }
            };
        }

        @Override
        public AutoCloseable admit(Caller caller, FlightRequest request) {
            if (busy) {
                throw new FlightRefusal(ErrorCode.BUSY_FLIGHT, "the server is very busy; try again in 30 seconds");
            }
            admitted.incrementAndGet();
            return () -> { };
        }

        @Override
        public List<FlightRequest> catalogue(Caller caller) {
            return List.of(new FlightRequest(FlightRequest.Form.KIND, JsonNodeFactory.instance.objectNode().put("kind", "trade"), -1, false, -1));
        }

        @Override
        public void audit(Audit record) {
            audits.add(record);
        }

        @Override
        public String nodeId() {
            return "n1";
        }
    }

    @BeforeEach
    void start() throws Exception {
        tls = new TlsFixture();
        backend = new Fake(10);
        start(FlightSettings.defaults(tls.path("server.pem"), tls.path("server.key")).on("127.0.0.1", 0));
        client = ArrowMemory.child("test-client", 0);
    }

    void start(FlightSettings s) throws Exception {
        if (service != null) {
            service.close();
        }
        service = new FlightService(s, backend, Clock.systemUTC());
        service.start();
    }

    @AfterEach
    void stop() {
        service.close();
        client.close();
        tls.delete();
    }

    FlightClient connect() throws Exception {
        try (InputStream ca = Files.newInputStream(tls.path("ca.pem"))) {
            return FlightClient.builder(client, Location.forGrpcTls("localhost", service.port())).trustedCertificates(ca).build();
        }
    }

    static byte[] kind(String kind, boolean stream, long resume) {
        return new FlightRequest(FlightRequest.Form.KIND, JsonNodeFactory.instance.objectNode().put("kind", kind), -1, stream, resume).toBytes();
    }

    @Test
    void aTokenOpensASessionAndASnapshotComesInBatchesWithEveryRowAndTheAudit() throws Exception {
        try (FlightClient c = connect()) {
            CallOption auth = c.authenticateBasicToken("app", TOKEN).orElseThrow();
            FlightInfo info = c.getInfo(FlightDescriptor.command(kind("trade", false, -1)), auth);
            assertThat(info.getEndpoints()).hasSize(1);
            assertThat(info.getEndpoints().get(0).getLocations()).extracting(l -> l.getUri().getHost()).containsExactly("127.0.0.1", "10.0.0.2");
            assertThat(info.getRecords()).isEqualTo(10);
            long rows = 0;
            int batches = 0;
            double sum = 0;
            try (FlightStream s = c.getStream(info.getEndpoints().get(0).getTicket(), auth)) {
                assertThat(s.getSchema().getCustomMetadata()).containsEntry("drishti.kind", "trade");
                while (s.next()) {
                    VectorSchemaRoot r = s.getRoot();
                    batches++;
                    rows += r.getRowCount();
                    Float8Vector v = (Float8Vector) r.getVector("VALUE");
                    for (int i = 0; i < r.getRowCount(); i++) {
                        sum += v.get(i);
                    }
                }
            }
            assertThat(rows).isEqualTo(10);
            assertThat(batches).isEqualTo(3);                              // 4 + 4 + 2
            assertThat(sum).isEqualTo(1.5 * 45);
        }
        assertThat(backend.audits).hasSize(1);
        assertThat(backend.audits.get(0).rows()).isEqualTo(10);
        assertThat(backend.audits.get(0).refusal()).isNull();
        assertThat(backend.audits.get(0).node()).isEqualTo("n1");
        assertThat(service.memory()[0]).as("no Arrow memory left behind").isZero();
    }

    @Test
    void noTokenAWrongTokenAndAScopeRefusalCarryTheirCodes() throws Exception {
        try (FlightClient c = connect()) {
            assertThatThrownBy(() -> c.listFlights(Criteria.ALL).forEach(x -> { }))
                    .isInstanceOf(FlightRuntimeException.class).hasMessageContaining("DRS-5601");
            assertThatThrownBy(() -> c.authenticateBasicToken("app", "drk_wrong"))
                    .isInstanceOf(FlightRuntimeException.class).hasMessageContaining("DRS-5601");
            CallOption auth = c.authenticateBasicToken("app", TOKEN).orElseThrow();
            assertThatThrownBy(() -> c.getInfo(FlightDescriptor.command(kind("secret", false, -1)), auth))
                    .isInstanceOf(FlightRuntimeException.class).hasMessageContaining("DRS-5602");
            assertThatThrownBy(() -> c.getInfo(FlightDescriptor.command("{not json".getBytes()), auth))
                    .isInstanceOf(FlightRuntimeException.class).hasMessageContaining("DRS-5603");
            List<FlightInfo> listed = new ArrayList<>();
            c.listFlights(Criteria.ALL, auth).forEach(listed::add);
            assertThat(listed).hasSize(1);
        }
    }

    @Test
    void theRowsLimitOfTheRoleStopsTheReadWithDrs5604AndTheAuditSaysSo() throws Exception {
        FlightSettings s = FlightSettings.defaults(tls.path("server.pem"), tls.path("server.key")).on("127.0.0.1", 0)
                .withLimits(new FlightLimits(6, 0, Duration.ZERO, 0), Map.of());
        start(s);
        try (FlightClient c = connect()) {
            CallOption auth = c.authenticateBasicToken("app", TOKEN).orElseThrow();
            long[] rows = {0};
            assertThatThrownBy(() -> {
                try (FlightStream st = c.getStream(new org.apache.arrow.flight.Ticket(kind("trade", false, -1)), auth)) {
                    while (st.next()) {
                        rows[0] += st.getRoot().getRowCount();
                    }
                }
            }).isInstanceOf(FlightRuntimeException.class).hasMessageContaining("DRS-5604");
            assertThat(rows[0]).as("whole batches only, never past the limit").isEqualTo(4);
        }
        assertThat(backend.audits.get(backend.audits.size() - 1).refusal()).startsWith("DRS-5604");
    }

    @Test
    void busyProtectionRefusesANewReadWithDrs9506() throws Exception {
        backend.busy = true;
        try (FlightClient c = connect()) {
            CallOption auth = c.authenticateBasicToken("app", TOKEN).orElseThrow();
            assertThatThrownBy(() -> {
                try (FlightStream st = c.getStream(new org.apache.arrow.flight.Ticket(kind("trade", false, -1)), auth)) {
                    while (st.next()) {
                        // nothing comes
                    }
                }
            }).isInstanceOf(FlightRuntimeException.class).hasMessageContaining("DRS-9506");
        }
    }

    @Test
    void aLiveStreamSendsEachBatchWithItsSequenceAndResumesAfterTheOneHeld() throws Exception {
        try (FlightClient c = connect()) {
            CallOption auth = c.authenticateBasicToken("app", TOKEN).orElseThrow();
            assertThat(sequences(c, auth, -1)).containsExactly(1L, 2L, 3L);
            assertThat(sequences(c, auth, 1)).as("resumed after 1").containsExactly(2L, 3L);
        }
    }

    List<Long> sequences(FlightClient c, CallOption auth, long resume) throws Exception {
        List<Long> seq = new ArrayList<>();
        try (FlightStream st = c.getStream(new org.apache.arrow.flight.Ticket(kind("trade", true, resume)), auth)) {
            while (st.next()) {
                assertThat(((BigIntVector) st.getRoot().getVector("ID")).get(0)).isZero();
                seq.add(st.getLatestMetadata().getLong(0));
            }
        }
        return seq;
    }

    @Test
    void healthAndWhoamiAnswerAsJson() throws Exception {
        try (FlightClient c = connect()) {
            CallOption auth = c.authenticateBasicToken("app", TOKEN).orElseThrow();
            Result who = c.doAction(new Action("whoami"), auth).next();
            assertThat(new String(who.getBody())).contains("\"person\":\"app\"").contains("\"scopes\":[\"data:read\"]");
            Result health = c.doAction(new Action("health"), auth).next();
            assertThat(new String(health.getBody())).contains("\"node\":\"n1\"");
        }
    }

    @Test
    void withoutACertificateTheServiceDoesNotStartAndSaysWhy() {
        FlightService s = new FlightService(FlightSettings.defaults(tls.path("missing.pem"), tls.path("missing.key")).on("127.0.0.1", 0),
                backend, Clock.systemUTC());
        assertThatThrownBy(s::start).hasMessageContaining("Arrow Flight needs TLS");
        assertThat(s.running()).isFalse();
    }

    @Test
    void anAdministratorsStopEndsALiveReadWithDrs5610() {
        FlightActivity a = new FlightActivity(Duration.ofDays(1), Clock.systemUTC());
        FlightActivity.Live l = a.begin(new FlightBackend.Caller("app", null, "t1", Set.of(), Set.of()),
                FlightRequest.parse(kind("trade", true, -1)), 0);
        assertThat(a.live()).hasSize(1);
        assertThat(a.stop(l.id(), "admin")).isTrue();
        assertThat(l.stopped()).isTrue();
        assertThat(l.stoppedBy()).isEqualTo("admin");
        assertThat(a.stop("nope", "admin")).isFalse();
    }
}
