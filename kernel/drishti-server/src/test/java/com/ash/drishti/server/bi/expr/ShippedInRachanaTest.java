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
package com.ash.drishti.server.bi.expr;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.bicompute.lang.Compiler;
import com.ash.drishti.bicompute.lang.SqlLowering;
import com.ash.rachana.el.CheckedMode;
import com.ash.rachana.el.Printer;
import com.ash.rachana.el.check.Fields;
import com.ash.rachana.el.check.Problem;
import com.ash.rachana.el.check.Scope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every expression of the shipped tables and models ({@code config/tables}, ) read as Rachana
 * (docs/architecture/ARCHITECTURE.md §28 2.4, 2.6 and 7.1): read as Rachana ({@link Printer}: the printed text reads back as the same text), checked in
 * its scope (measures in aggregate scope, rules in rule scope, the rest in row scope) and lowered by the one compiler without a problem;
 * The values of the same expressions in Java and in SQL are compared by
 * drishti-bicompute's DifferentialTest; the parity of BI's figures is part B's.
 */
class ShippedInRachanaTest {

    record Shipped(String file, String path, String del, Scope scope) {}

    static List<Shipped> shipped() throws IOException {
        List<Shipped> out = new ArrayList<>();
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        for (String dir : List.of("../../config/tables/bi", "../../config/tables/banking")) {
            try (Stream<Path> files = Files.list(Path.of(dir))) {
                for (Path f : files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList()) {
                    walk(yaml.readTree(f.toFile()), f.getFileName().toString(), "", out);
                }
            }
        }
        return out;
    }

    private static void walk(JsonNode n, String file, String path, List<Shipped> out) {
        if (n.isObject()) {
            n.properties().forEach(e -> {
                String p = path + "/" + e.getKey();
                if (("expr".equals(e.getKey()) || "where".equals(e.getKey()) || "filter".equals(e.getKey())) && e.getValue().isTextual()) {
                    Scope s = p.contains("/measures/") ? Scope.AGGREGATE : p.contains("/security/") || p.startsWith("/rules") ? Scope.RULE : Scope.ROW;
                    out.add(new Shipped(file, p, e.getValue().asText(), s));
                } else {
                    walk(e.getValue(), file, p, out);
                }
            });
        } else if (n.isArray()) {
            for (int i = 0; i < n.size(); i++) {
                walk(n.get(i), file, path + "[" + i + "]", out);
            }
        }
    }

    @Test
    void everyShippedExpressionReadsAsRachanaAndCompiles() throws IOException {
        List<Shipped> all = shipped();
        assertThat(all).hasSizeGreaterThan(60);
        Compiler compiler = new Compiler(parts -> "\"" + String.join(".", parts) + "\"", SqlLowering.Options.of(LocalDate.of(2026, 10, 16)));
        List<String> nullRule = new ArrayList<>();
        for (Shipped s : all) {
            String where = s.file() + s.path() + ": " + s.del();
            String rachana = s.del();
            String printed = Printer.print(CheckedMode.parse(rachana, com.ash.rachana.profile.Grammar.LEVEL).expr());
            assertThat(Printer.print(CheckedMode.parse(printed, com.ash.rachana.profile.Grammar.LEVEL).expr())).as(where + " printed " + printed)
                    .isEqualTo(printed);                                       // Rachana's one spelling reads back as itself
            assertThat(CheckedMode.check(rachana, s.scope(), Fields.NONE).problems().stream().filter(Problem::error)).as(where + " => " + rachana).isEmpty();
            if (s.scope() == Scope.AGGREGATE) {
                assertThat(compiler.measure(CheckedMode.parse(rachana, com.ash.rachana.profile.Grammar.LEVEL).expr())).as(where).isNotNull();
            } else if (!rachana.contains("user.")) {
                assertThat(compiler.row(CheckedMode.parse(rachana, com.ash.rachana.profile.Grammar.LEVEL).expr()).sql()).as(where).isNotBlank();
            }
            if (rachana.contains("!=")) {
                nullRule.add(where);                                           // the migration's null-rule rewrite applies here (Q1)
            }
        }
        // the comparisons whose meaning changes for a null under Rachana's rule are few, and the migrator rewrote each (Q1)
        assertThat(nullRule).hasSizeLessThan(10);
    }
}
