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
package com.ash.drishti.server.flight;

import com.ash.drishti.common.Branding;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.flight.FlightBackend;
import com.ash.drishti.flight.FlightSqlCatalog;
import com.ash.drishti.server.bi.BiAccess;
import com.ash.drishti.server.bi.BiService;
import com.ash.drishti.server.bi.mq.ModelCatalog;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

/**
 * Flight SQL's catalog from the BI model catalogue, as the person may see it (docs/guides/FLIGHT_GUIDE.md, "Flight SQL"): a schema per
 * model they may read ({@link ModelCatalog#list}), a table per alias of the model with the columns its description gives them (hidden
 * fields left out, as {@link ModelCatalog#describe} does). Only with the BI power and the {@code data:read} scope opening {@code bi}; any
 * refusal lists nothing rather than failing a tool's browse.
 */
final class ServerFlightSqlCatalog implements FlightSqlCatalog {

    private final ModelCatalog catalog;
    private final BiService bi;

    ServerFlightSqlCatalog(ModelCatalog catalog, BiService bi) {
        this.catalog = catalog;
        this.bi = bi;
    }

    @Override
    public String catalog() {
        String name = Branding.name("data").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        return name.isBlank() ? "data" : name;
    }

    @Override
    public String version() {
        String v = getClass().getPackage().getImplementationVersion();
        return v == null ? "" : v;
    }

    private Principal person(FlightBackend.Caller c) {
        if (!c.scopes().contains(ServerFlightBackend.SCOPE) && !c.scopes().contains(ServerFlightBackend.SCOPE + ":bi")
                && !c.scopes().contains(ServerFlightBackend.SCOPE + ":*")) {
            return null;
        }
        Principal p = ServerFlightBackend.principal(c);
        return bi.hasPower(p, BiAccess.BI) ? p : null;
    }

    @Override
    public List<String> schemas(FlightBackend.Caller caller) {
        Principal p = person(caller);
        List<String> out = new ArrayList<>();
        if (p == null) {
            return out;
        }
        try {
            for (Map<String, Object> m : catalog.list(p)) {
                out.add(String.valueOf(m.get("id")));
            }
        } catch (DrishtiException e) {
            return List.of();
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Table> tables(FlightBackend.Caller caller, String schema) {
        Principal p = person(caller);
        List<Table> out = new ArrayList<>();
        if (p == null) {
            return out;
        }
        Map<String, Object> d;
        try {
            d = catalog.describe(p, schema);
        } catch (DrishtiException e) {
            return out;
        }
        for (Map<String, Object> set : (List<Map<String, Object>>) d.getOrDefault("datasets", List.of())) {
            String alias = String.valueOf(set.get("alias"));
            List<Field> fields = new ArrayList<>();
            for (Map<String, Object> f : (List<Map<String, Object>>) set.getOrDefault("fields", List.of())) {
                String name = String.valueOf(f.get("field"));
                name = name.startsWith(alias + ".") ? name.substring(alias.length() + 1) : name;
                fields.add(new Field(name, FieldType.nullable(type(String.valueOf(f.get("type")))), null));
            }
            out.add(new Table(schema, alias, new Schema(fields)));
        }
        return out;
    }

    /** A model column's type as the answers carry it (ArrowIpcWriter: text, 64-bit integers, doubles). */
    static ArrowType type(String t) {
        String s = t == null ? "" : t.toLowerCase(Locale.ROOT);
        if (s.contains("int") || s.equals("long") || s.equals("bigint")) {
            return new ArrowType.Int(64, true);
        }
        if (s.contains("decimal") || s.contains("double") || s.contains("float") || s.contains("number") || s.contains("amount")
                || s.contains("rate") || s.contains("numeric")) {
            return new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
        }
        return ArrowType.Utf8.INSTANCE;
    }

}
