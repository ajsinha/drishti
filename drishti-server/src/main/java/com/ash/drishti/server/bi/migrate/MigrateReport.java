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
package com.ash.drishti.server.bi.migrate;

import java.util.List;

/**
 * The converter's plan report, {@code migrate-report.md} (docs/architecture/BI_MODEL_SPEC.md section 8.3, step 4): every item, what it became, every name
 * change, every behaviour difference (section 8.5) and every manual step, in the order a person reads them. Pure.
 */
final class MigrateReport {

    private MigrateReport() {}

    static String markdown(List<ModelConverter.Item> items) {
        StringBuilder b = new StringBuilder("# Dataset-to-model migration report\n\n");
        long converted = items.stream().filter(i -> "converted".equals(i.state())).count();
        long removed = items.stream().filter(i -> "removed".equals(i.state())).count();
        long manual = items.stream().filter(i -> !i.manual().isEmpty()).count();
        long problems = items.stream().filter(i -> "problem".equals(i.state())).count();
        b.append(items.size()).append(" item(s): ").append(converted).append(" converted, ").append(removed).append(" folded into a model, ").append(manual)
                .append(" with manual steps, ").append(problems).append(" refused.\n\n");
        b.append("| Item | Kind | State | Became |\n|---|---|---|---|\n");
        for (ModelConverter.Item i : items) {
            b.append("| `").append(i.key()).append("` | ").append(i.kind()).append(" | ").append(i.state()).append(" | ")
                    .append(i.became() == null ? i.problem() == null ? "" : i.problem().replace("|", "\\|") : i.became().replace("|", "\\|")).append(" |\n");
        }
        for (ModelConverter.Item i : items) {
            if (i.outputs().isEmpty() && i.changes().isEmpty() && i.notes().isEmpty() && i.manual().isEmpty() && i.problem() == null) {
                continue;
            }
            b.append("\n## ").append(i.key()).append(" (").append(i.state()).append(")\n\n");
            if (i.problem() != null) {
                b.append("**Refused:** ").append(i.problem()).append("\n\n");
            }
            if (!i.outputs().isEmpty()) {
                b.append("Writes:\n\n");
                i.outputs().forEach(o -> b.append("* `").append(o.path()).append("` (").append(o.kind()).append(" ").append(o.name()).append(")\n"));
                b.append('\n');
            }
            section(b, "Changes", i.changes());
            section(b, "Behaviour differences and notes", i.notes());
            section(b, "Manual steps", i.manual());
        }
        return b.toString();
    }

    private static void section(StringBuilder b, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        b.append(title).append(":\n\n");
        lines.forEach(l -> b.append("* ").append(l).append('\n'));
        b.append('\n');
    }
}
