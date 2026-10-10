package com.ash.drishti.server.bi;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityHit;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.SourcePlugin;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.engine.source.SourceRegistry;
import com.ash.drishti.engine.source.SourceRouter;
import com.ash.drishti.engine.time.BusinessDates;
import com.ash.drishti.server.bi.engine.QueryProperties;
import com.ash.drishti.server.bi.engine.SourceGateway;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The engine's way to the data on the server: the connectors by their logical names ({@code config/connectors/<name>.yaml}), through the
 * connector registry, and the router for the slow path of reading documents. There is no BI-only route to a store (RUPAKA.md section 6.1).
 */
public final class ServerSourceGateway implements SourceGateway {

    private final SourceRegistry registry;
    private final SourceRouter router;
    private final BusinessDates dates;
    private final QueryProperties props;

    public ServerSourceGateway(SourceRegistry registry, SourceRouter router, BusinessDates dates, QueryProperties props) {
        this.registry = registry;
        this.router = router;
        this.dates = dates;
        this.props = props;
    }

    @Override
    public Optional<SourcePlugin> plugin(String connector, String kind) {
        if (connector != null && !connector.isBlank()) {
            Optional<SourcePlugin> named = registry.connector(connector);
            if (named.isEmpty()) {
                String why = registry.connectorStatuses().containsKey(connector) ? " (" + registry.connectorStatuses().get(connector).state().toLowerCase() + ")" : " (no such connector)";
                throw new DrishtiException(ErrorCode.QUERY_SOURCE, "connector '" + connector + "' is not running" + why + ": check Admin, Connectors");
            }
            return named;
        }
        return registry.plugins().stream().filter(p -> p.manifest().serves(kind)).filter(p -> !p.manifest().kinds().isEmpty())
                .max(Comparator.comparingInt((SourcePlugin p) -> p.columnar(kind).size())).or(() -> registry.plugins().stream().filter(p -> p.manifest().serves(kind)).findFirst());
    }

    @Override
    public List<EntityRef> ids(String kind, LocalDate date, int limit) {
        List<EntityHit> hits = router.search(kind, "", limit, props.recordReadBudget(), asOf(date));
        return hits.stream().map(EntityHit::ref).toList();
    }

    @Override
    public Map<EntityRef, EntityDocument> read(Collection<EntityRef> refs, LocalDate date) {
        return router.fetchAll(refs, props.recordReadBudget(), asOf(date));
    }

    private AsOf asOf(LocalDate date) {
        return dates.resolve(date == null ? AsOf.LATEST : AsOf.of(date));
    }

    @Override
    public LocalDate currentDate() {
        return dates.current();
    }
}
