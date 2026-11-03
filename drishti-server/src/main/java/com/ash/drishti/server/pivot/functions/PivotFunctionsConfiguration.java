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
package com.ash.drishti.server.pivot.functions;

import com.ash.drishti.bicompute.core.AggregationPlan.FunctionCall;
import com.ash.drishti.bicompute.core.PivotCore;
import com.ash.drishti.bicompute.custom.CustomFunctions;
import com.ash.drishti.bicompute.custom.JavaPlugins;
import com.ash.drishti.bicompute.custom.SqlMacroFunction;
import com.ash.drishti.bicompute.spi.PivotProvider;
import com.ash.drishti.bicompute.spi.Viewer;
import com.ash.drishti.bicompute.sql.DuckDbExecutor;
import com.ash.drishti.bicompute.sql.TabularProvider;
import com.ash.drishti.compute.worker.Sdk;
import com.ash.drishti.identity.AuditLog;
import com.ash.drishti.identity.content.ContentStore;
import com.ash.drishti.server.security.Entitlements;
import com.ash.drishti.server.security.Principal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Custom pivot functions on this server (PIVOT_GUIDE.md, custom functions): the store in the content store, who may approve (an
 * administrator, or a member of {@code approver-role}), the audit log, the SQL runner for holistic macros (the pivot executor), the
 * {@code pivot-fn} Python pool (only when Python functions are on) and the plug-in folder (only when Java plug-ins are on); then the engine's
 * core resolves {@code fn:owner/name@v(...)} through them, and the tabular provider feeds functions without an SQL form in batches.
 */
@Configuration
@EnableConfigurationProperties(PivotFunctionProperties.class)
public class PivotFunctionsConfiguration {

    @Bean(destroyMethod = "close")
    public PythonPool pivotPythonPool(PivotFunctionProperties props, @Value("${drishti.data.dir:./data}") String dataDir) {
        if (!props.python().enabled()) {
            return new PythonPool(null);
        }
        Path sdk = Sdk.install(Path.of(dataDir).resolve("compute").resolve("sdk"));
        PivotFunctionProperties.Python p = props.python();
        return new PythonPool(new ProcessPythonClient(p.interpreter(), sdk, p.workers(), p.timeoutSeconds(), p.memoryMb()));
    }

    /** The Python pool, or none when Python functions are off. */
    public record PythonPool(ProcessPythonClient client) implements AutoCloseable {
        @Override
        public void close() {
            if (client != null) {
                client.close();
            }
        }
    }

    @Bean
    public CustomFunctions pivotCustomFunctions(PivotFunctionProperties props, ContentStore store, Entitlements entitlements, AuditLog audit,
            DuckDbExecutor exec, PythonPool python, PivotCore core) {
        CustomFunctions.Access access = new CustomFunctions.Access() {
            @Override
            public Set<String> groups(Viewer v) {
                return v.principal() instanceof Principal p && p.roles() != null ? new LinkedHashSet<>(p.roles()) : Set.of();
            }

            @Override
            public boolean approver(Viewer v) {
                return admin(v) || groups(v).contains(props.approverRole());
            }

            @Override
            public boolean admin(Viewer v) {
                return v.principal() instanceof Principal p && entitlements.isAdmin(p);
            }
        };
        SqlMacroFunction.Runner sql = (body, params, rows, columns) -> runMacro(exec, body, params, rows, columns);
        JavaPlugins plugins = props.java().enabled() ? new JavaPlugins(Path.of(props.java().dir()).toAbsolutePath()) : null;
        CustomFunctions fns = new CustomFunctions(new PivotFunctionStore(store), access, audit::record, props.settings(), python.client(), sql,
                plugins);
        core.functions((v, text, label) -> {
            if (!CustomFunctions.isCall(text)) {
                return Optional.empty();
            }
            CustomFunctions.Resolved r = fns.resolve(v, text);
            return Optional.of(new FunctionCall(r.id(), label == null || label.isBlank() ? r.id() + " by " + r.def().owner() : label.strip(),
                    r.fn(), r.fields()));
        });
        for (PivotProvider p : core.providers()) {
            if (p instanceof TabularProvider t) {
                t.feed(props.python().batchRows(), props.holisticMaxRows());
            }
        }
        return fns;
    }

    @Bean
    public com.ash.drishti.server.pivot.PivotViewVersions pivotViewVersions(com.ash.drishti.server.pivot.PivotViews views) {
        return new com.ash.drishti.server.pivot.PivotViewVersions(views);
    }

    /** A holistic macro's body over a cell's rows, in the pivot executor: the rows staged as a VALUES list of bound parameters. */
    static Double runMacro(DuckDbExecutor exec, String body, List<String> params, int rows, Object[] columns) {
        if (rows == 0) {
            return null;
        }
        if (rows > 1_000_000) {
            throw new com.ash.drishti.common.DrishtiException(com.ash.drishti.common.ErrorCode.PIVOT_FUNCTION_LIMIT, "a macro outside a query "
                    + "runs over at most 1,000,000 rows here (the playground and the harness): the tabular provider computes it in its query");
        }
        StringBuilder values = new StringBuilder();
        List<Object> bound = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            values.append(r == 0 ? "(" : ", (");
            for (int c = 0; c < columns.length; c++) {
                values.append(c == 0 ? "?" : ", ?");
                if (columns[c] instanceof double[] d) {
                    bound.add(Double.isNaN(d[r]) ? null : d[r]);
                } else {
                    bound.add(((String[]) columns[c])[r]);
                }
            }
            values.append(")");
        }
        List<String> cols = params.stream().map(DuckDbExecutor::quote).toList();
        String sql = "SELECT " + body + " FROM (VALUES " + values + ") AS \"m\"(" + String.join(", ", cols) + ")";
        return exec.query(sql, bound, rs -> {
            if (!rs.next()) {
                return null;
            }
            Object o = rs.getObject(1);
            return o instanceof Number n && !Double.isNaN(n.doubleValue()) ? n.doubleValue() : null;
        });
    }
}
