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

import com.ash.drishti.common.ErrorCode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightStream;
import org.apache.arrow.flight.PutResult;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.SchemaResult;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.flight.sql.NoOpFlightSqlProducer;
import org.apache.arrow.flight.sql.SqlInfoBuilder;
import org.apache.arrow.flight.sql.impl.FlightSql;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.WriteChannel;
import org.apache.arrow.vector.ipc.message.MessageSerializer;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Flight SQL on Drishti's Flight port (docs/guides/FLIGHT_GUIDE.md, "Flight SQL"; FLIGHT_REFERENCE.md section 6): read-only SQL over BI
 * models, written in DSQL (BI_LANGUAGE_REFERENCE.md) and answered by the server's own DSQL path, never a second SQL engine. A statement is
 * turned into a {@code form: model} request ({@code {"model": …, "sql": …}}) and read through the same producer as every Flight read:
 * the token, the scope, busy protection, the limits, Data out and the audit apply unchanged.
 *
 * <p>The model a statement reads is named by a first-line comment ({@code -- model: risk-summary@1} or {@code /* model: … *\/}), else
 * by the call's header {@code model} (a JDBC URL's {@code model=}). Catalog calls: one catalog (the product), a schema per model the
 * person may read, a table per alias of the model. Prepared statements are read-only and stateless (the handle is the request itself);
 * parameters, updates, ingestion, Substrait and transactions are refused.
 */
public final class DrishtiSqlProducer extends NoOpFlightSqlProducer {

    private static final Pattern MODEL_COMMENT = Pattern.compile("^\\s*(?:--\\s*model\\s*:\\s*([^\\s]+)|/\\*\\s*model\\s*:\\s*([^\\s*]+)\\s*\\*/)",
            Pattern.CASE_INSENSITIVE);
    private static final Schema EMPTY = new Schema(List.of());

    private final DrishtiFlightProducer main;
    private final FlightBackend backend;
    private final FlightSqlCatalog catalog;
    private final BufferAllocator allocator;

    public DrishtiSqlProducer(DrishtiFlightProducer main, FlightBackend backend, FlightSqlCatalog catalog, BufferAllocator allocator) {
        this.main = main;
        this.backend = backend;
        this.catalog = catalog;
        this.allocator = allocator;
    }

    // ------------------------------------------------------------------------------------------------------------ statements

    /** The request a statement is: {@code form: model} with the model and the SQL text. */
    static FlightRequest request(String sql, String headerModel) {
        if (sql == null || sql.isBlank()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "the statement is empty");
        }
        Matcher m = MODEL_COMMENT.matcher(sql);
        String model = m.find() ? (m.group(1) != null ? m.group(1) : m.group(2)) : headerModel;
        if (model == null || model.isBlank()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "name the model the statement reads: a first line '-- model: <model>' or the "
                    + "connection's model= (the header model); the schemas list the models you may read");
        }
        if (sql.strip().replaceFirst("^(--[^\\n]*\\n|/\\*.*?\\*/)\\s*", "").toUpperCase(Locale.ROOT).startsWith("EXPLAIN")) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "EXPLAIN is answered by the BI SQL console and POST /api/v1/bi/sql, not over Flight SQL");
        }
        ObjectNode o = JsonNodeFactory.instance.objectNode().put("model", model.strip()).put("sql", sql);
        return new FlightRequest(FlightRequest.Form.MODEL, o, -1, false, -1);
    }

    private static byte[] statementTicket(byte[] request) {
        return Any.pack(FlightSql.TicketStatementQuery.newBuilder().setStatementHandle(ByteString.copyFrom(request)).build()).toByteArray();
    }

    @Override
    public FlightInfo getFlightInfoStatement(FlightSql.CommandStatementQuery command, CallContext context, FlightDescriptor descriptor) {
        try {
            return main.info(context, request(command.getQuery(), CallHeadersMiddleware.header(context, "model")), descriptor,
                    DrishtiSqlProducer::statementTicket);
        } catch (FlightRefusal r) {
            throw r.toFlight();
        }
    }

    @Override
    public SchemaResult getSchemaStatement(FlightSql.CommandStatementQuery command, CallContext context, FlightDescriptor descriptor) {
        return new SchemaResult(schema(context, request(command.getQuery(), CallHeadersMiddleware.header(context, "model"))));
    }

    @Override
    public void getStreamStatement(FlightSql.TicketStatementQuery ticket, CallContext context, ServerStreamListener listener) {
        FlightRequest r;
        try {
            r = FlightRequest.parse(ticket.getStatementHandle().toByteArray());
        } catch (FlightRefusal e) {
            listener.error(e.toFlight());
            return;
        }
        main.read(context, r, listener);
    }

    private Schema schema(CallContext context, FlightRequest r) {
        try {
            return backend.plan(main.caller(context), r).schema();
        } catch (FlightRefusal e) {
            throw e.toFlight();
        }
    }

    // ------------------------------------------------------------------------------------------------------------ prepared statements

    @Override
    public void createPreparedStatement(FlightSql.ActionCreatePreparedStatementRequest request, CallContext context, StreamListener<Result> listener) {
        try {
            FlightRequest r = request(request.getQuery(), CallHeadersMiddleware.header(context, "model"));
            Schema schema = schema(context, r);
            FlightSql.ActionCreatePreparedStatementResult result = FlightSql.ActionCreatePreparedStatementResult.newBuilder()
                    .setPreparedStatementHandle(ByteString.copyFrom(r.toBytes()))
                    .setDatasetSchema(ByteString.copyFrom(serialize(schema)))
                    .setParameterSchema(ByteString.copyFrom(serialize(EMPTY)))
                    .build();
            listener.onNext(new Result(Any.pack(result).toByteArray()));
            listener.onCompleted();
        } catch (FlightRefusal e) {
            listener.onError(e.toFlight());
        } catch (RuntimeException e) {
            listener.onError(e);
        }
    }

    @Override
    public void closePreparedStatement(FlightSql.ActionClosePreparedStatementRequest request, CallContext context, StreamListener<Result> listener) {
        listener.onCompleted();                                              // stateless: the handle is the request itself
    }

    @Override
    public FlightInfo getFlightInfoPreparedStatement(FlightSql.CommandPreparedStatementQuery command, CallContext context, FlightDescriptor descriptor) {
        try {
            return main.info(context, FlightRequest.parse(command.getPreparedStatementHandle().toByteArray()), descriptor,
                    DrishtiSqlProducer::statementTicket);
        } catch (FlightRefusal r) {
            throw r.toFlight();
        }
    }

    @Override
    public SchemaResult getSchemaPreparedStatement(FlightSql.CommandPreparedStatementQuery command, CallContext context, FlightDescriptor descriptor) {
        return new SchemaResult(schema(context, FlightRequest.parse(command.getPreparedStatementHandle().toByteArray())));
    }

    @Override
    public void getStreamPreparedStatement(FlightSql.CommandPreparedStatementQuery command, CallContext context, ServerStreamListener listener) {
        FlightRequest r;
        try {
            r = FlightRequest.parse(command.getPreparedStatementHandle().toByteArray());
        } catch (FlightRefusal e) {
            listener.error(e.toFlight());
            return;
        }
        main.read(context, r, listener);
    }

    @Override
    public Runnable acceptPutPreparedStatementQuery(FlightSql.CommandPreparedStatementQuery command, CallContext context, FlightStream flightStream,
            StreamListener<PutResult> ackStream) {
        return () -> ackStream.onError(readOnly("statement parameters"));
    }

    @Override
    public Runnable acceptPutStatement(FlightSql.CommandStatementUpdate command, CallContext context, FlightStream flightStream,
            StreamListener<PutResult> ackStream) {
        return () -> ackStream.onError(readOnly("an update"));
    }

    @Override
    public Runnable acceptPutPreparedStatementUpdate(FlightSql.CommandPreparedStatementUpdate command, CallContext context, FlightStream flightStream,
            StreamListener<PutResult> ackStream) {
        return () -> ackStream.onError(readOnly("an update"));
    }

    private static RuntimeException readOnly(String what) {
        return CallStatus.UNIMPLEMENTED.withDescription(ErrorCode.FLIGHT_BAD_TICKET.code() + ": Flight SQL is read only here: " + what
                + " is refused (SELECT over a model only)").toRuntimeException();
    }

    // ------------------------------------------------------------------------------------------------------------ the catalog

    private FlightInfo listing(com.google.protobuf.Message command, FlightDescriptor descriptor, Schema schema) {
        return new FlightInfo(schema, descriptor, List.of(new FlightEndpoint(new Ticket(Any.pack(command).toByteArray()))), -1, -1);
    }

    @Override
    public FlightInfo getFlightInfoCatalogs(FlightSql.CommandGetCatalogs request, CallContext context, FlightDescriptor descriptor) {
        return listing(request, descriptor, Schemas.GET_CATALOGS_SCHEMA);
    }

    @Override
    public void getStreamCatalogs(CallContext context, ServerStreamListener listener) {
        send(listener, Schemas.GET_CATALOGS_SCHEMA, List.<String[]>of(new String[] {catalog.catalog()}));
    }

    @Override
    public FlightInfo getFlightInfoSchemas(FlightSql.CommandGetDbSchemas request, CallContext context, FlightDescriptor descriptor) {
        return listing(request, descriptor, Schemas.GET_SCHEMAS_SCHEMA);
    }

    @Override
    public void getStreamSchemas(FlightSql.CommandGetDbSchemas command, CallContext context, ServerStreamListener listener) {
        List<String[]> rows = new ArrayList<>();
        if (!command.hasCatalog() || command.getCatalog().equals(catalog.catalog())) {
            Pattern like = like(command.hasDbSchemaFilterPattern() ? command.getDbSchemaFilterPattern() : null);
            for (String s : catalog.schemas(main.caller(context))) {
                if (like.matcher(s).matches()) {
                    rows.add(new String[] {catalog.catalog(), s});
                }
            }
        }
        send(listener, Schemas.GET_SCHEMAS_SCHEMA, rows);
    }

    @Override
    public FlightInfo getFlightInfoTables(FlightSql.CommandGetTables request, CallContext context, FlightDescriptor descriptor) {
        return listing(request, descriptor, request.getIncludeSchema() ? Schemas.GET_TABLES_SCHEMA : Schemas.GET_TABLES_SCHEMA_NO_SCHEMA);
    }

    @Override
    public void getStreamTables(FlightSql.CommandGetTables command, CallContext context, ServerStreamListener listener) {
        boolean withSchema = command.getIncludeSchema();
        Schema schema = withSchema ? Schemas.GET_TABLES_SCHEMA : Schemas.GET_TABLES_SCHEMA_NO_SCHEMA;
        List<String> types = command.getTableTypesList();
        List<FlightSqlCatalog.Table> tables = new ArrayList<>();
        FlightBackend.Caller caller = main.caller(context);
        if ((!command.hasCatalog() || command.getCatalog().equals(catalog.catalog())) && (types.isEmpty() || types.contains("TABLE"))) {
            Pattern schemas = like(command.hasDbSchemaFilterPattern() ? command.getDbSchemaFilterPattern() : null);
            Pattern names = like(command.hasTableNameFilterPattern() ? command.getTableNameFilterPattern() : null);
            for (String s : catalog.schemas(caller)) {
                if (!schemas.matcher(s).matches()) {
                    continue;
                }
                for (FlightSqlCatalog.Table t : catalog.tables(caller, s)) {
                    if (names.matcher(t.name()).matches()) {
                        tables.add(t);
                    }
                }
            }
        }
        try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
            root.allocateNew();
            for (int i = 0; i < tables.size(); i++) {
                FlightSqlCatalog.Table t = tables.get(i);
                ((VarCharVector) root.getVector("catalog_name")).setSafe(i, utf8(catalog.catalog()));
                ((VarCharVector) root.getVector("db_schema_name")).setSafe(i, utf8(t.schema()));
                ((VarCharVector) root.getVector("table_name")).setSafe(i, utf8(t.name()));
                ((VarCharVector) root.getVector("table_type")).setSafe(i, utf8("TABLE"));
                if (withSchema) {
                    ((VarBinaryVector) root.getVector("table_schema")).setSafe(i, serialize(t.columns()));
                }
            }
            root.setRowCount(tables.size());
            listener.start(root);
            listener.putNext();
            listener.completed();
        }
    }

    @Override
    public FlightInfo getFlightInfoTableTypes(FlightSql.CommandGetTableTypes request, CallContext context, FlightDescriptor descriptor) {
        return listing(request, descriptor, Schemas.GET_TABLE_TYPES_SCHEMA);
    }

    @Override
    public void getStreamTableTypes(CallContext context, ServerStreamListener listener) {
        send(listener, Schemas.GET_TABLE_TYPES_SCHEMA, List.<String[]>of(new String[] {"TABLE"}));
    }

    @Override
    public FlightInfo getFlightInfoSqlInfo(FlightSql.CommandGetSqlInfo request, CallContext context, FlightDescriptor descriptor) {
        return listing(request, descriptor, Schemas.GET_SQL_INFO_SCHEMA);
    }

    @Override
    public void getStreamSqlInfo(FlightSql.CommandGetSqlInfo command, CallContext context, ServerStreamListener listener) {
        new SqlInfoBuilder()
                .withFlightSqlServerName(catalog.catalog())
                .withFlightSqlServerVersion(catalog.version())
                .withFlightSqlServerArrowVersion(org.apache.arrow.vector.types.pojo.Schema.class.getPackage().getImplementationVersion() == null
                        ? "" : org.apache.arrow.vector.types.pojo.Schema.class.getPackage().getImplementationVersion())
                .withFlightSqlServerReadOnly(true)
                .withFlightSqlServerSql(true)
                .withFlightSqlServerSubstrait(false)
                .withFlightSqlServerTransaction(FlightSql.SqlSupportedTransaction.SQL_SUPPORTED_TRANSACTION_NONE)
                .withFlightSqlServerCancel(false)
                .withSqlIdentifierQuoteChar("\"")
                .withSqlSchemaTerm("model")
                .withSqlCatalogTerm("catalog")
                .withSqlDdlCatalog(false)
                .withSqlDdlSchema(false)
                .withSqlDdlTable(false)
                .withSqlSupportsColumnAliasing(true)
                .send(command.getInfoList(), listener);
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

    /** Rows of text in a fixed schema whose fields are all utf8. */
    private void send(ServerStreamListener listener, Schema schema, List<String[]> rows) {
        try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, allocator)) {
            root.allocateNew();
            for (int r = 0; r < rows.size(); r++) {
                for (int c = 0; c < schema.getFields().size(); c++) {
                    String v = rows.get(r)[c];
                    VarCharVector vec = (VarCharVector) root.getVector(c);
                    if (v == null) {
                        vec.setNull(r);
                    } else {
                        vec.setSafe(r, utf8(v));
                    }
                }
            }
            root.setRowCount(rows.size());
            listener.start(root);
            listener.putNext();
            listener.completed();
        }
    }

    /** A SQL LIKE pattern ({@code %}, {@code _}) as a regex; null matches everything. */
    static Pattern like(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return Pattern.compile(".*", Pattern.DOTALL);
        }
        StringBuilder b = new StringBuilder();
        for (char ch : pattern.toCharArray()) {
            b.append(switch (ch) {
                case '%' -> ".*";
                case '_' -> ".";
                default -> Pattern.quote(String.valueOf(ch));
            });
        }
        return Pattern.compile(b.toString(), Pattern.DOTALL);
    }

    /** A schema as an Arrow IPC message (what Flight SQL carries in table_schema and dataset_schema). */
    static byte[] serialize(Schema schema) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            MessageSerializer.serialize(new WriteChannel(Channels.newChannel(out)), schema);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
