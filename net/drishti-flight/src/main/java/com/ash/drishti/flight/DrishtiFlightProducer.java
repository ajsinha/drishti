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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.apache.arrow.flight.Action;
import org.apache.arrow.flight.ActionType;
import org.apache.arrow.flight.BackpressureStrategy;
import org.apache.arrow.flight.CallStatus;
import org.apache.arrow.flight.Criteria;
import org.apache.arrow.flight.FlightDescriptor;
import org.apache.arrow.flight.FlightEndpoint;
import org.apache.arrow.flight.FlightInfo;
import org.apache.arrow.flight.FlightRuntimeException;
import org.apache.arrow.flight.Location;
import org.apache.arrow.flight.NoOpFlightProducer;
import org.apache.arrow.flight.Result;
import org.apache.arrow.flight.SchemaResult;
import org.apache.arrow.flight.Ticket;
import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.OutOfMemoryException;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drishti's Arrow Flight producer (docs/guides/FLIGHT_GUIDE.md). Read only (decided 3):
 * <ul>
 *   <li>{@code GetFlightInfo} / {@code GetSchema}: a descriptor whose command is a {@link FlightRequest}; the answer lists one endpoint per
 *       part with the real addresses of the nodes that hold it, preferred first (Q5);</li>
 *   <li>{@code DoGet}: a ticket from those endpoints (or a request without a part: everything, read here); a snapshot, or with
 *       {@code stream: true} a live stream whose batches carry their sequence number (8 bytes, little-endian) as app metadata, resumed with
 *       {@code resume} (Q7);</li>
 *   <li>{@code ListFlights}: what the caller may read; {@code DoAction}: {@code health}, {@code whoami}.</li>
 * </ul>
 * Every read is admitted by busy protection, held to the caller's limits and its own Arrow memory cap (Q8), shown on Admin → Data out while
 * it runs and audited when it ends (Q9).
 */
public final class DrishtiFlightProducer extends NoOpFlightProducer {

    private static final Logger log = LoggerFactory.getLogger(DrishtiFlightProducer.class);
    private static final Duration POLL = Duration.ofMillis(250);

    private final FlightBackend backend;
    private final FlightSettings settings;
    private final TokenAuthenticator auth;
    private final FlightActivity activity;
    private final BufferAllocator allocator;
    private final Clock clock;
    private volatile DrishtiSqlProducer sql;

    public DrishtiFlightProducer(FlightBackend backend, FlightSettings settings, TokenAuthenticator auth, FlightActivity activity,
            BufferAllocator allocator, Clock clock) {
        this.backend = backend;
        this.settings = settings;
        this.auth = auth;
        this.activity = activity;
        this.allocator = allocator;
        this.clock = clock;
    }

    /** Flight SQL on the same port (docs/guides/FLIGHT_GUIDE.md, Flight SQL): its commands and actions go to {@code sql}. */
    public DrishtiFlightProducer sql(DrishtiSqlProducer s) {
        this.sql = s;
        return this;
    }

    /** True when the bytes are a Flight SQL command or ticket (a protobuf {@code Any} of arrow.flight.protocol.sql), not Drishti's JSON. */
    static boolean flightSql(byte[] bytes) {
        if (bytes == null || bytes.length < 2 || bytes[0] != 0x0A) {
            return false;                                                   // JSON starts with '{'; an Any with its type URL (field 1)
        }
        try {
            return com.google.protobuf.Any.parseFrom(bytes).getTypeUrl().startsWith("type.googleapis.com/arrow.flight.protocol.sql.");
        } catch (com.google.protobuf.InvalidProtocolBufferException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------------------------------------------------ plans

    @Override
    public FlightInfo getFlightInfo(CallContext context, FlightDescriptor descriptor) {
        DrishtiSqlProducer s = sql;
        if (s != null && descriptor.isCommand() && flightSql(descriptor.getCommand())) {
            caller(context);                                                 // the token first, whatever the command
            return s.getFlightInfo(context, descriptor);
        }
        return info(context, request(descriptor), descriptor, java.util.function.UnaryOperator.identity());
    }

    /**
     * The plan of a request as Flight answers it: one endpoint per part with its nodes; {@code ticket} wraps each part's request (Flight
     * SQL puts it in a statement ticket). Refusals are audited.
     */
    FlightInfo info(CallContext context, FlightRequest request, FlightDescriptor descriptor, java.util.function.UnaryOperator<byte[]> ticket) {
        FlightBackend.Caller caller = caller(context);
        try {
            FlightBackend.Plan plan = backend.plan(caller, request);
            List<FlightEndpoint> endpoints = new ArrayList<>(plan.parts().size());
            for (int p = 0; p < plan.parts().size(); p++) {
                FlightBackend.Part part = plan.parts().get(p);
                Location[] at = part.nodes().stream().map(n -> Location.forGrpcTls(n.host(), n.port())).toArray(Location[]::new);
                endpoints.add(new FlightEndpoint(new Ticket(ticket.apply(request.forPart(p).toBytes())), at));
            }
            return new FlightInfo(plan.schema(), descriptor, endpoints, plan.bytes(), plan.rows());
        } catch (FlightRefusal r) {
            audit(caller, request, -1, 0, 0, clock.instant(), r.code().code() + ": " + r.getMessage());
            throw r.toFlight();
        }
    }

    @Override
    public SchemaResult getSchema(CallContext context, FlightDescriptor descriptor) {
        FlightBackend.Caller caller = caller(context);
        DrishtiSqlProducer s = sql;
        if (s != null && descriptor.isCommand() && flightSql(descriptor.getCommand())) {
            return s.getSchema(context, descriptor);
        }
        try {
            return new SchemaResult(backend.plan(caller, request(descriptor)).schema());
        } catch (FlightRefusal r) {
            throw r.toFlight();
        }
    }

    @Override
    public void listFlights(CallContext context, Criteria criteria, StreamListener<FlightInfo> listener) {
        FlightBackend.Caller caller = caller(context);
        try {
            for (FlightRequest r : backend.catalogue(caller)) {
                FlightDescriptor d = FlightDescriptor.command(r.toBytes());
                listener.onNext(new FlightInfo(new org.apache.arrow.vector.types.pojo.Schema(List.of()), d, List.of(), -1, -1));
            }
            listener.onCompleted();
        } catch (FlightRefusal r) {
            listener.onError(r.toFlight());
        }
    }

    @Override
    public void listActions(CallContext context, StreamListener<ActionType> listener) {
        if (sql != null) {
            org.apache.arrow.flight.sql.FlightSqlUtils.FLIGHT_SQL_ACTIONS.stream()
                    .filter(a -> a.getType().equals(org.apache.arrow.flight.sql.FlightSqlUtils.FLIGHT_SQL_CREATE_PREPARED_STATEMENT.getType())
                            || a.getType().equals(org.apache.arrow.flight.sql.FlightSqlUtils.FLIGHT_SQL_CLOSE_PREPARED_STATEMENT.getType()))
                    .forEach(listener::onNext);
        }
        listener.onNext(new ActionType("health", "the service's state on this node: up, Arrow memory in use and its cap, live reads"));
        listener.onNext(new ActionType("whoami", "the person, the person acted for, the token id, the roles, scopes and limits"));
        listener.onCompleted();
    }

    @Override
    public void doAction(CallContext context, Action action, StreamListener<Result> listener) {
        FlightBackend.Caller caller = caller(context);
        DrishtiSqlProducer s = sql;
        if (s != null && org.apache.arrow.flight.sql.FlightSqlUtils.FLIGHT_SQL_ACTIONS.stream().anyMatch(a -> a.getType().equals(action.getType()))) {
            s.doAction(context, action, listener);
            return;
        }
        String body = switch (action.getType()) {
            case "health" -> "{\"node\":" + quote(backend.nodeId()) + ",\"arrowBytes\":" + allocator.getAllocatedMemory()
                    + ",\"arrowCap\":" + allocator.getLimit() + ",\"live\":" + activity.live().size() + "}";
            case "whoami" -> {
                FlightLimits l = settings.limitsFor(caller.roles());
                yield "{\"person\":" + quote(caller.person()) + ",\"onBehalfOf\":" + quote(caller.onBehalfOf()) + ",\"tokenId\":"
                        + quote(caller.tokenId()) + ",\"roles\":" + list(caller.roles()) + ",\"scopes\":" + list(caller.scopes())
                        + ",\"limits\":{\"rows\":" + l.rows() + ",\"bytes\":" + l.bytes() + ",\"seconds\":" + l.time().toSeconds()
                        + ",\"memory\":" + l.memory() + "}}";
            }
            default -> {
                listener.onError(CallStatus.UNIMPLEMENTED.withDescription("actions: health, whoami").toRuntimeException());
                yield null;
            }
        };
        if (body != null) {
            listener.onNext(new Result(body.getBytes(StandardCharsets.UTF_8)));
            listener.onCompleted();
        }
    }

    // ------------------------------------------------------------------------------------------------------------ reads

    @Override
    public void getStream(CallContext context, Ticket ticket, ServerStreamListener listener) {
        DrishtiSqlProducer s = sql;
        if (s != null && flightSql(ticket.getBytes())) {
            try {
                caller(context);
            } catch (FlightRuntimeException e) {
                listener.error(e);
                return;
            }
            s.getStream(context, ticket, listener);
            return;
        }
        FlightRequest request;
        try {
            request = FlightRequest.parse(ticket.getBytes());
        } catch (FlightRefusal r) {
            listener.error(r.toFlight());
            return;
        }
        read(context, request, listener);
    }

    /** One read of a request (a Drishti ticket, or a Flight SQL statement's): admitted, limited, shown on Data out, audited. */
    void read(CallContext context, FlightRequest request, ServerStreamListener listener) {
        FlightBackend.Caller caller;
        try {
            caller = caller(context);
        } catch (FlightRuntimeException e) {
            listener.error(e);
            return;
        }
        FlightLimits limits = settings.limitsFor(caller.roles());
        int part = Math.max(0, request.part());
        FlightActivity.Live live = activity.begin(caller, request, part);
        Instant started = clock.instant();
        String refusal = null;
        try (AutoCloseable permit = backend.admit(caller, request);
                BufferAllocator mine = allocator.newChildAllocator("flight-" + live.id(), 0,
                        limits.memory() <= 0 ? settings.requestMemory() : limits.memory())) {
            if (request.stream()) {
                stream(caller, request, part, listener, mine, limits, live, started);
            } else {
                snapshot(caller, request, part, listener, mine, limits, live, started);
            }
            listener.completed();
        } catch (FlightRefusal r) {
            refusal = r.code().code() + ": " + r.getMessage();
            listener.error(r.toFlight());
        } catch (OutOfMemoryException e) {
            FlightRefusal r = new FlightRefusal(ErrorCode.FLIGHT_MEMORY, "the read passed its Arrow memory cap ("
                    + (limits.memory() <= 0 ? settings.requestMemory() : limits.memory()) + " bytes, or the node's "
                    + settings.totalMemory() + "): ask for fewer columns or a narrower scope", e);
            refusal = r.code().code() + ": " + r.getMessage();
            listener.error(r.toFlight());
        } catch (Cancelled c) {
            refusal = "cancelled by the client";
        } catch (Exception e) {
            log.warn("Flight read {} failed: {}", live.id(), e.toString());
            refusal = "failed: " + e.getMessage();
            listener.error(CallStatus.INTERNAL.withDescription("the read failed on node " + backend.nodeId() + ": " + e.getMessage())
                    .withCause(e).toRuntimeException());
        } finally {
            FlightBackend.Audit a = audit(caller, request, part, live.rows(), live.bytes(), started, refusal);
            activity.end(live, a);
        }
    }

    private void snapshot(FlightBackend.Caller caller, FlightRequest request, int part, ServerStreamListener listener, BufferAllocator mine,
            FlightLimits limits, FlightActivity.Live live, Instant started) throws Exception {
        BackpressureStrategy pressure = new BackpressureStrategy.CallbackBackpressureStrategy();
        pressure.register(listener);
        try (FlightBackend.BatchSource source = backend.open(caller, request, part);
                VectorSchemaRoot root = VectorSchemaRoot.create(source.schema(), mine)) {
            listener.start(root);
            while (source.next(root)) {
                check(live, limits, started, root.getRowCount(), bytes(root));
                await(pressure, listener, limits, started);
                listener.putNext();
                live.sent(root.getRowCount(), bytes(root), -1, mine.getAllocatedMemory());
            }
        }
    }

    private void stream(FlightBackend.Caller caller, FlightRequest request, int part, ServerStreamListener listener, BufferAllocator mine,
            FlightLimits limits, FlightActivity.Live live, Instant started) throws Exception {
        BackpressureStrategy pressure = new BackpressureStrategy.CallbackBackpressureStrategy();
        pressure.register(listener);
        try (FlightBackend.Subscription sub = backend.subscribe(caller, request, part);
                VectorSchemaRoot root = VectorSchemaRoot.create(sub.schema(), mine)) {
            listener.start(root);
            while (true) {
                if (listener.isCancelled()) {
                    throw new Cancelled();
                }
                if (live.stopped()) {
                    throw new FlightRefusal(ErrorCode.FLIGHT_STOPPED, "stopped by " + live.stoppedBy() + " on Admin → Data out");
                }
                long seq = sub.next(root, POLL);
                if (seq == FlightBackend.Subscription.END) {
                    return;
                }
                if (seq == FlightBackend.Subscription.NONE) {
                    continue;
                }
                long b = bytes(root);
                check(live, limits, null, root.getRowCount(), b);    // a live stream has no end: no time limit on its whole life
                await(pressure, listener, limits, clock.instant());
                ArrowBuf meta = mine.buffer(8);                       // putNext takes the buffer and releases it once sent
                meta.writeLong(seq);
                listener.putNext(meta);
                live.sent(root.getRowCount(), b, seq, mine.getAllocatedMemory());
            }
        }
    }

    /** Refuses the batch about to be sent when it passes the limits, or when an administrator stopped the read. */
    private void check(FlightActivity.Live live, FlightLimits limits, Instant started, long rows, long bytes) {
        if (live.stopped()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_STOPPED, "stopped by " + live.stoppedBy() + " on Admin → Data out");
        }
        if (limits.rows() > 0 && live.rows() + rows > limits.rows()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_ROWS_LIMIT, "the read passed " + limits.rows()
                    + " rows, the limit of your role; " + live.rows() + " rows were sent: narrow the request or read it in parts");
        }
        if (limits.bytes() > 0 && live.bytes() + bytes > limits.bytes()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BYTES_LIMIT, "the read passed " + limits.bytes()
                    + " bytes, the limit of your role; " + live.bytes() + " bytes were sent");
        }
        if (started != null && !limits.time().isZero() && Duration.between(started, clock.instant()).compareTo(limits.time()) > 0) {
            throw new FlightRefusal(ErrorCode.FLIGHT_TIME_LIMIT, "the read ran past " + limits.time().toSeconds()
                    + " seconds, the limit of your role");
        }
    }

    /** Waits until the client can take another batch (Flight's flow control), within the time limit. */
    private void await(BackpressureStrategy pressure, ServerStreamListener listener, FlightLimits limits, Instant started) {
        long left = limits.time().isZero() ? 60_000 : Math.max(1, limits.time().toMillis() - Duration.between(started, clock.instant()).toMillis());
        BackpressureStrategy.WaitResult w = pressure.waitForListener(left);
        if (w == BackpressureStrategy.WaitResult.CANCELLED || listener.isCancelled()) {
            throw new Cancelled();
        }
        if (w == BackpressureStrategy.WaitResult.TIMEOUT) {
            throw new FlightRefusal(ErrorCode.FLIGHT_TIME_LIMIT, "the client did not take the next batch within "
                    + Duration.ofMillis(left).toSeconds() + " seconds");
        }
    }

    private static long bytes(VectorSchemaRoot root) {
        long b = 0;
        for (FieldVector v : root.getFieldVectors()) {
            b += v.getBufferSize();
        }
        return b;
    }

    // ------------------------------------------------------------------------------------------------------------ helpers

    FlightBackend.Caller caller(CallContext context) {
        try {
            return auth.caller(context.peerIdentity());
        } catch (FlightRefusal r) {
            throw r.toFlight();
        }
    }

    private static FlightRequest request(FlightDescriptor d) {
        if (!d.isCommand()) {
            throw new FlightRefusal(ErrorCode.FLIGHT_BAD_TICKET, "a descriptor is a command (a request as JSON), not a path").toFlight();
        }
        try {
            return FlightRequest.parse(d.getCommand());
        } catch (FlightRefusal r) {
            throw r.toFlight();
        }
    }

    private FlightBackend.Audit audit(FlightBackend.Caller c, FlightRequest r, int part, long rows, long bytes, Instant started, String refusal) {
        FlightBackend.Audit a = new FlightBackend.Audit(clock.instant(), c.person(), c.onBehalfOf(), c.tokenId(),
                r.form().name().toLowerCase(java.util.Locale.ROOT), r.text(), part, r.stream(), rows, bytes,
                Duration.between(started, clock.instant()).toMillis(), backend.nodeId(), refusal);
        try {
            backend.audit(a);
        } catch (RuntimeException e) {
            log.warn("Flight audit failed: {}", e.toString());
        }
        return a;
    }

    private static String quote(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String list(java.util.Collection<String> v) {
        return v.stream().sorted().map(DrishtiFlightProducer::quote).collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /** The client went away: nothing to send, the read ends quietly (audited as cancelled). */
    private static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("cancelled", null, false, false);
        }
    }
}
