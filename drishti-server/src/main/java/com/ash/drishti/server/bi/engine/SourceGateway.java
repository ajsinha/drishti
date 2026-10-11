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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.api.Subscription;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * How the engine reaches the data: the same connectors as the rest of Drishti, by their logical names (RUPAKA.md section 6.1). The server's
 * implementation goes through the connector registry and router; tests give it a small generated lake.
 */
public interface SourceGateway {

    /**
     * The connector for a table: the named one, or whichever running connector serves the kind when the name is blank.
     *
     * @throws com.ash.drishti.common.DrishtiException DRS-9026 when the named connector is not running
     */
    Optional<SourcePlugin> plugin(String connector, String kind);

    /** Ids of the kind on the business date (null for the latest), at most {@code limit}; the slow path for sources that cannot scan columns. */
    List<EntityRef> ids(String kind, LocalDate date, int limit);

    /** The documents of these entities as of the date (null for the latest). */
    Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, LocalDate date);

    /**
     * Follows an entity's live updates: through the named connector when {@code connector} is not blank (a stream that keeps no state but ticks what a store
     * serves), else through whichever live source holds the entity (the router's choice, as a live view makes). {@link Subscription#NONE} when nothing pushes it.
     */
    default Subscription subscribe(String connector, EntityRef ref, Consumer<EntityDocument> listener) {
        return Subscription.NONE;
    }

    /** The current business date. */
    LocalDate currentDate();
}
