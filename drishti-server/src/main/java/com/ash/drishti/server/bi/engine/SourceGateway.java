package com.ash.drishti.server.bi.engine;

import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** The current business date. */
    LocalDate currentDate();
}
