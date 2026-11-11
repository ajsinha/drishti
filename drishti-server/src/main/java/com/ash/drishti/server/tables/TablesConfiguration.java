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
package com.ash.drishti.server.tables;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.engine.ViewPipeline;
import com.ash.drishti.engine.wire.TableRows;
import com.ash.drishti.server.data.DataViewService;
import com.ash.drishti.server.edition.EditionCommands;
import com.ash.drishti.server.security.SecurityProperties;
import com.ash.drishti.tables.read.TableReads;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * The virtual tables' beans ({@code drishti.tables.*}, BI_GUIDE.md section T7a): the catalogue under {@code drishti.tables.root} and the
 * reads for a viewer (the request's {@link com.ash.drishti.engine.ViewerContext}: who, their attributes, masks and kinds), and the view
 * pipeline's port for panels whose source is a virtual table (docs/architecture/ARCHITECTURE.md V13).
 */
@Configuration
public class TablesConfiguration {

    @Bean
    public TablesService tablesService(Environment env, DataViewService data, com.ash.drishti.engine.source.SourceRouter router,
            EditionCommands edition, SecurityProperties security, com.ash.drishti.identity.bi.BiStore store) {
        Path root = Path.of(env.getProperty("drishti.tables.root", "./config/tables")).toAbsolutePath().normalize();
        long maxRows = env.getProperty("drishti.tables.read-max-rows", Long.class, 2_000_000L);
        long warn = env.getProperty("drishti.tables.scan-warn-rows", Long.class, 1_000_000L);
        TablesService tables = new TablesService(root, data, router, edition, security::enabled, maxRows, warn, java.time.Clock.systemUTC());
        tables.itemStore(new BiItemTableStore(store));
        tables.domains(com.ash.drishti.server.modules.DomainFolders.on(env));    // an off module's tables and models are not read                     // the editors' published tables and models beside the files (Q16)
        return tables;
    }

    /** The editors of tables, models and Python-driven tables (docs/architecture/SHARED_SCREENS.md sections 1 to 4): their API is {@link EditorController}. */
    @Bean
    public EditorService tablesEditorService(TablesService tables, DataViewService data, com.ash.drishti.server.security.Entitlements entitlements,
            com.ash.drishti.server.bi.BiAccess access, com.ash.drishti.server.bi.BiService bi, com.ash.drishti.identity.PreferenceStore preferences,
            com.ash.drishti.server.security.RoleCatalog roles, SecurityProperties security,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.python.PythonRuns> runs,
            org.springframework.beans.factory.ObjectProvider<com.ash.drishti.server.bi.python.PythonFrames> frames) {
        EditorService editors = new EditorService(tables, data, entitlements, access, bi, preferences, roles, security.enabled());
        editors.python(runs.getIfAvailable(), frames.getIfAvailable());          // the sample run of a Python-driven table, on the compute pool
        return editors;
    }

    /** A Sutra panel's {@code source: { table }}: the table read for the page's viewer, its rows as objects of its columns. */
    @Bean
    public TableRows sutraTableRows(TablesService tables, ViewPipeline pipeline) {
        TableRows rows = (who, table, date) -> {
            try {
                TableReads.Read r = tables.read(tables.reads(who), who, table, date == null ? null : date.toString(), null, null);
                List<DataNode> out = new ArrayList<>(r.rows().size());
                for (List<Object> row : r.rows()) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    for (int i = 0; i < r.columns().size(); i++) {
                        Object v = row.get(i);
                        m.put(r.columns().get(i), DataNode.MASK.equals(v) ? DataNode.masked() : v);
                    }
                    out.add(DataNode.of(m));
                }
                return out;
            } catch (DrishtiException e) {
                throw new TableRows.Refused(e.getMessage());
            }
        };
        pipeline.tables(rows);
        return rows;
    }
}
