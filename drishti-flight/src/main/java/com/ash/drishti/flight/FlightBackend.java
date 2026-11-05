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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * What the Flight service needs from the server, and all it gets: the server implements this over its existing token services,
 * entitlements, masks, busy protection, audit and query layer (kinds, BI models, the pivot engine, the measures, the in-memory lake), so
 * Flight is a transport only and nothing is written twice (docs/architecture/ARROW_FLIGHT.md, decided 1). Every method may throw
 * {@link FlightRefusal} with the reason.
 */
public interface FlightBackend {

    /**
     * The caller of a token (Q3): an API token or an on-behalf-of token, checked by the token services; {@code onBehalfOf} is the
     * person an application names (only an on-behalf-of token may name one). A browser session is never accepted.
     */
    Caller authenticate(String token, String onBehalfOf);

    /**
     * The plan of a request: its schema, its parts and the nodes that hold each (Q5), the rows and bytes expected (-1: not known). Checks
     * the scope {@code data:read} of the request's domain and the entitlements of the person.
     */
    Plan plan(Caller caller, FlightRequest request);

    /** The batches of one part of a request, read here (the snapshot). Entitlements and masks are applied by the query layer. */
    BatchSource open(Caller caller, FlightRequest request, int part);

    /** A live stream of one part (Q7): batches as they come, each with its sequence, from after {@code request.resume()}. */
    Subscription subscribe(Caller caller, FlightRequest request, int part);

    /** Busy protection's admission (Q8): refuses with {@code DRS-9506} when the node is too busy; the permit is closed when the read ends. */
    AutoCloseable admit(Caller caller, FlightRequest request);

    /** The requests this caller may read, for {@code ListFlights} (kinds, models, measures, lake tables by name; never their data). */
    List<FlightRequest> catalogue(Caller caller);

    /** Writes one audit record (Q9) into the server's audit. */
    void audit(Audit record);

    /** This node's id and real address (host), for the endpoints and the audit. */
    String nodeId();

    /**
     * A caller.
     *
     * @param person     who the token belongs to (an application's service person for an on-behalf-of token)
     * @param onBehalfOf the person the application acts for, or null
     * @param tokenId    the token's id (never the secret)
     * @param roles      the roles that decide the limits and entitlements (the person acted for, when on behalf)
     * @param scopes     the token's scopes ({@code data:read:<domain>}, {@code data:read:*})
     */
    record Caller(String person, String onBehalfOf, String tokenId, Set<String> roles, Set<String> scopes) {
        public Caller {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }

        /** The person whose rights apply. */
        public String effective() {
            return onBehalfOf == null || onBehalfOf.isBlank() ? person : onBehalfOf;
        }
    }

    /** One part of a plan: a label (a shard, the lake owner), the nodes that hold it (first: the preferred), rows expected or -1. */
    record Part(String label, List<Node> nodes, long rows) {
        public Part {
            nodes = List.copyOf(nodes);
        }
    }

    /** A node of the cluster with its real address and Flight port. */
    record Node(String id, String host, int port) {}

    /** A plan: the schema every part answers with, the parts, and the totals expected (-1: not known). */
    record Plan(Schema schema, List<Part> parts, long rows, long bytes) {
        public Plan {
            parts = List.copyOf(parts);
        }
    }

    /** Snapshot batches: {@link #next} fills the root (its schema is {@link #schema}) and answers false at the end. */
    interface BatchSource extends AutoCloseable {
        Schema schema();

        boolean next(VectorSchemaRoot root);

        @Override
        default void close() {}
    }

    /**
     * A live stream: {@link #next} fills the root with the next batch and answers its sequence, {@link #NONE} when nothing came within the
     * wait, {@link #END} when the stream ended (the date closed, the source stopped).
     */
    interface Subscription extends AutoCloseable {
        long NONE = -1;
        long END = -2;

        Schema schema();

        long next(VectorSchemaRoot root, Duration wait);

        @Override
        default void close() {}
    }

    /** One audit record: who, for whom, what, how much, from which node, and how it ended (null: completed). */
    record Audit(Instant at, String person, String onBehalfOf, String tokenId, String form, String request, int part, boolean stream,
            long rows, long bytes, long millis, String node, String refusal) {}
}
