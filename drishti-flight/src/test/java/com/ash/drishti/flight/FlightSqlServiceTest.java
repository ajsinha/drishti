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
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.arrow.flight.CallOption;
import org.apache.arrow.flight.FlightClient;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.HeaderCallOption;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.sql.FlightSqlClient;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Flight SQL in process, over TLS with a stand-in backend (FLIGHT_GUIDE.md, Flight SQL): a statement names its model by a first-line
 * comment or the header {@code model}, becomes a {@code form: model} request with the SQL, and is read through the same path as every
 * Flight read (audited); the catalog lists one catalog, the models as schemas, their aliases as tables with columns; a prepared statement
 * is read-only and carries its result schema; an update and a statement without a model are refused with words.
 */
class FlightSqlServiceTest {

    TlsFixture tls;
    Backend backend;
    FlightService service;
    BufferAllocator client;

    /** Answers a model statement with DESK, PNL; records what it was asked. */
    static final class Backend implements FlightBackend {
        final List<FlightRequest> asked = new CopyOnWriteArrayList<>();
        final List<Audit> audits = new CopyOnWriteArrayList<>();

        ArrowTable table() {
            return ArrowTable.builder().strings("DESK", "RATES", "FX").doubles("PNL", -10.5, 4.0).build();
        }

        @Override
        public Caller authenticate(String token, String onBehalfOf) {
            if (!"drk_good".equals(token)) {
                throw new FlightRefusal(ErrorCode.FLIGHT_UNAUTHENTICATED, "the API token is unknown, revoked or expired");
            }
            return new Caller("analyst", null, "t1", Set.of("bi-reader"), Set.of("data:read"));
        }

        @Override
        public Plan plan(Caller caller, FlightRequest request) {
            asked.add(request);
            return new Plan(new TableBatches(table(), Map.of(), 100).schema(), List.of(new Part("model", List.of(new Node("n1", "127.0.0.1", 0)), 2)), 2, -1);
        }

        @Override
        public BatchSource open(Caller caller, FlightRequest request, int part) {
            asked.add(request);
            return new TableBatches(table(), Map.of(), 100);
        }

        @Override
        public Subscription subscribe(Caller caller, FlightRequest request, int part) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "snapshot only");
        }

        @Override
        public AutoCloseable admit(Caller caller, FlightRequest request) {
            return () -> { };
        }

        @Override
        public List<FlightRequest> catalogue(Caller caller) {
            return List.of();
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

    static final FlightSqlCatalog CATALOG = new FlightSqlCatalog() {
        @Override
        public String catalog() {
            return "data";
        }

        @Override
        public List<String> schemas(FlightBackend.Caller caller) {
            return List.of("risk-summary@1", "trading@2");
        }

        @Override
        public List<Table> tables(FlightBackend.Caller caller, String schema) {
            return List.of(new Table(schema, "trades", new Schema(List.of(new Field("DESK", FieldType.nullable(ArrowType.Utf8.INSTANCE), null)))));
        }

        @Override
        public String version() {
            return "2.9.11";
        }
    };

    @BeforeEach
    void start() throws Exception {
        tls = new TlsFixture();
        backend = new Backend();
        service = new FlightService(FlightSettings.defaults(tls.path("server.pem"), tls.path("server.key")).on("127.0.0.1", 0), backend,
                Clock.systemUTC(), CATALOG);
        service.start();
        client = ArrowMemory.child("sql-test-client", 0);
    }

    @AfterEach
    void stop() {
        service.close();
        client.close();
        tls.delete();
    }

    FlightSqlClient connect(List<CallOption> auth) throws Exception {
        try (InputStream ca = Files.newInputStream(tls.path("ca.pem"))) {
            FlightClient c = FlightClient.builder(client, Location.forGrpcTls("localhost", service.port())).trustedCertificates(ca).build();
            auth.add(c.authenticateBasicToken("analyst", "drk_good").orElseThrow());
            return new FlightSqlClient(c);
        }
    }

    static List<List<Object>> rows(FlightSqlClient c, FlightInfo info, CallOption... o) throws Exception {
        List<List<Object>> out = new ArrayList<>();
        try (FlightStream s = c.getStream(info.getEndpoints().get(0).getTicket(), o)) {
            while (s.next()) {
                VectorSchemaRoot r = s.getRoot();
                for (int i = 0; i < r.getRowCount(); i++) {
                    List<Object> row = new ArrayList<>();
                    for (int k = 0; k < r.getFieldVectors().size(); k++) {
                        Object v = r.getVector(k).getObject(i);
                        row.add(v == null ? null : v.toString());
                    }
                    out.add(row);
                }
            }
        }
        return out;
    }

    @Test
    void aStatementNamingItsModelIsReadAsAModelRequestAndAudited() throws Exception {
        List<CallOption> auth = new ArrayList<>();
        try (FlightSqlClient c = connect(auth)) {
            CallOption o = auth.get(0);
            FlightInfo info = c.execute("-- model: risk-summary@1\nSELECT trades.DESK, MEASURE('PnL') FROM trades GROUP BY trades.DESK", o);
            assertThat(info.getSchema().getFields()).extracting(Field::getName).containsExactly("DESK", "PNL");
            assertThat(rows(c, info, o)).containsExactly(List.of("RATES", "-10.5"), List.of("FX", "4.0"));
        }
        FlightRequest r = backend.asked.get(backend.asked.size() - 1);
        assertThat(r.form()).isEqualTo(FlightRequest.Form.MODEL);
        assertThat(r.request().get("model").asText()).isEqualTo("risk-summary@1");
        assertThat(r.request().get("sql").asText()).contains("FROM trades");
        assertThat(backend.audits).anySatisfy(a -> {
            assertThat(a.form()).isEqualTo("model");
            assertThat(a.rows()).isEqualTo(2);
        });
    }

    @Test
    void theHeaderModelNamesTheModelWhenTheStatementDoesNot() throws Exception {
        List<CallOption> auth = new ArrayList<>();
        try (FlightSqlClient c = connect(auth)) {
            org.apache.arrow.flight.FlightCallHeaders h = new org.apache.arrow.flight.FlightCallHeaders();
            h.insert("model", "trading@2");
            FlightInfo info = c.execute("SELECT trades.DESK FROM trades", auth.get(0), new HeaderCallOption(h));
            assertThat(rows(c, info, auth.get(0))).hasSize(2);
            assertThat(backend.asked.get(backend.asked.size() - 1).request().get("model").asText()).isEqualTo("trading@2");
            assertThatThrownBy(() -> c.execute("SELECT trades.DESK FROM trades", auth.get(0))).isInstanceOf(FlightRuntimeException.class)
                    .hasMessageContaining("DRS-5603").hasMessageContaining("model");
        }
    }

    @Test
    void theCatalogListsTheModelsAsSchemasAndTheirAliasesAsTables() throws Exception {
        List<CallOption> auth = new ArrayList<>();
        try (FlightSqlClient c = connect(auth)) {
            CallOption o = auth.get(0);
            assertThat(rows(c, c.getCatalogs(o), o)).containsExactly(List.of("data"));
            assertThat(rows(c, c.getSchemas(null, "risk%", o), o)).containsExactly(List.of("data", "risk-summary@1"));
            List<List<Object>> tables = rows(c, c.getTables(null, "trading@2", null, null, false, o), o);
            assertThat(tables).containsExactly(List.of("data", "trading@2", "trades", "TABLE"));
            assertThat(rows(c, c.getTableTypes(o), o)).containsExactly(List.of("TABLE"));
        }
    }

    @Test
    void aPreparedStatementCarriesItsSchemaAndAnUpdateIsRefused() throws Exception {
        List<CallOption> auth = new ArrayList<>();
        try (FlightSqlClient c = connect(auth)) {
            CallOption o = auth.get(0);
            try (FlightSqlClient.PreparedStatement ps = c.prepare("/* model: risk-summary@1 */ SELECT trades.DESK FROM trades", o)) {
                assertThat(ps.getResultSetSchema().getFields()).extracting(Field::getName).containsExactly("DESK", "PNL");
                FlightInfo info = ps.execute(o);
                assertThat(rows(c, info, o)).hasSize(2);
            }
            assertThatThrownBy(() -> c.executeUpdate("-- model: risk-summary@1\nDELETE FROM trades", o)).isInstanceOf(FlightRuntimeException.class);
        }
    }

    @Test
    void aStatementRequestIsTheModelFormWithItsSql() {
        FlightRequest r = DrishtiSqlProducer.request("-- model: m@1\nSELECT 1", null);
        assertThat(r.form()).isEqualTo(FlightRequest.Form.MODEL);
        assertThat(r.request().get("model").asText()).isEqualTo("m@1");
        assertThat(DrishtiSqlProducer.request("SELECT 1", "x@2").request().get("model").asText()).isEqualTo("x@2");
        assertThatThrownBy(() -> DrishtiSqlProducer.request("-- model: m@1\nEXPLAIN SELECT 1", null)).hasMessageContaining("EXPLAIN");
        assertThat(DrishtiSqlProducer.like("risk%").matcher("risk-summary@1").matches()).isTrue();
        assertThat(DrishtiSqlProducer.like("r_sk").matcher("risk").matches()).isTrue();
        assertThat(DrishtiSqlProducer.like("a.b").matcher("axb").matches()).isFalse();
    }
}
