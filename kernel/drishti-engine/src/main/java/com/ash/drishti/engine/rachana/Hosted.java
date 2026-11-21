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
package com.ash.drishti.engine.rachana;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.Provenance;
import com.ash.rachana.bind.Entity;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Drishti's types at the edge of Rachana's binder (SPLIT_PLAN 8.3e): an entity reference, a point in time and a document each have a
 * Drishti record (the sources, the API and the live hub speak them) and a Rachana one (the binder, the controls and the event wiring
 * speak them). One place converts both ways, field for field; a Rachana {@link Entity} made here carries its Drishti
 * {@link EntityDocument} as its host object, so a document goes back unchanged.
 */
public final class Hosted {

    private Hosted() {
    }

    public static com.ash.rachana.bind.EntityRef ref(com.ash.drishti.api.EntityRef r) {
        return r == null ? null : new com.ash.rachana.bind.EntityRef(r.kind(), r.id());
    }

    public static com.ash.drishti.api.EntityRef ref(com.ash.rachana.bind.EntityRef r) {
        return r == null ? null : new com.ash.drishti.api.EntityRef(r.kind(), r.id());
    }

    public static Set<com.ash.drishti.api.EntityRef> refs(Collection<com.ash.rachana.bind.EntityRef> refs) {
        Set<com.ash.drishti.api.EntityRef> out = new LinkedHashSet<>();
        refs.forEach(r -> out.add(ref(r)));
        return out;
    }

    public static com.ash.rachana.bind.AsOf asOf(com.ash.drishti.api.AsOf a) {
        return a == null ? null : new com.ash.rachana.bind.AsOf(a.businessDate(), a.knownAt(), a.live());
    }

    public static com.ash.drishti.api.AsOf asOf(com.ash.rachana.bind.AsOf a) {
        return a == null ? null : new com.ash.drishti.api.AsOf(a.businessDate(), a.knownAt(), a.live());
    }

    public static com.ash.rachana.bind.EntityHit hit(com.ash.drishti.api.EntityHit h) {
        return new com.ash.rachana.bind.EntityHit(ref(h.ref()), h.title(), h.subtitle());
    }

    /** A Drishti document as the binder's entity: its data, deletion, origin (source, fetched at) and the document itself as host object. */
    public static Entity entity(EntityDocument d) {
        if (d == null) {
            return null;
        }
        Provenance p = d.provenance();
        return new Entity(ref(d.ref()), d.data(), d.deleted(), p == null ? null : new Entity.Origin(p.source(), p.fetchedAt()), d);
    }

    /** The Drishti document of a binder entity: the one it was made from, or (an entity a host made otherwise) rebuilt from its fields. */
    public static EntityDocument doc(Entity e) {
        if (e == null) {
            return null;
        }
        if (e.host() instanceof EntityDocument d && d.data() == e.data()) {
            return d;
        }
        EntityDocument from = e.host() instanceof EntityDocument d ? d : null;
        Provenance p = from != null ? from.provenance()
                : new Provenance(e.origin() == null ? null : e.origin().source(), 0, e.origin() == null ? null : e.origin().fetchedAt(), false, null);
        return new EntityDocument(ref(e.ref()), DataNode.of(e.data()), p, e.deleted());
    }

    /** Documents by Drishti reference as entities by Rachana reference (order kept). */
    public static Map<com.ash.rachana.bind.EntityRef, Entity> entities(Map<com.ash.drishti.api.EntityRef, EntityDocument> docs) {
        Map<com.ash.rachana.bind.EntityRef, Entity> out = new LinkedHashMap<>();
        docs.forEach((r, d) -> out.put(ref(r), entity(d)));
        return out;
    }

    /** A Drishti document's provenance as a binder entity's origin. */
    public static Entity.Origin origin(Provenance p) {
        return p == null ? null : new Entity.Origin(p.source(), p.fetchedAt());
    }
}
