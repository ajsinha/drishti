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
package com.ash.drishti.server.dist;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.server.bi.ai.BiAiService;
import com.ash.drishti.server.security.Principal;
import java.util.ArrayList;
import java.util.List;

/**
 * The narrative summary of a subscription ({@code narrative: true}), written through the existing LLM layer under the feature {@code subscription-narrative}. It is
 * asked of the model per recipient and only from that recipient's own render: the facts are the report's title, date and the tables as the recipient sees them, with every
 * column masked for them left out, cut to a few rows each. It is written only when the AI policy lets the model see values ({@code drishti.bi.ai.values: aggregates});
 * under {@code labels-only}, with the AI layer off, or when the policy refuses this recipient (feature roles, limits), the mail goes without it and the delivery says why.
 */
public final class AiNarrator implements SubscriptionService.Narrator {

    private final BiAiService ai;
    private final int rows;

    public AiNarrator(BiAiService ai, int rows) {
        this.ai = ai;
        this.rows = Math.max(1, rows);
    }

    @Override
    public String unavailable() {
        return ai.enabled() ? null : "it is switched off (drishti.ai.enabled)";
    }

    @Override
    public SubscriptionService.Narration write(Principal recipient, ExportDoc doc) {
        try {
            return SubscriptionService.Narration.of(ai.narrate(recipient, facts(doc, rows)));
        } catch (DrishtiException e) {
            return SubscriptionService.Narration.skipped(e.getMessage());
        } catch (RuntimeException e) {
            return SubscriptionService.Narration.skipped("the model could not be reached");
        }
    }

    /** The recipient's own figures as text for the model: no masked column, at most {@code maxRows} rows of a table, nothing from outside the document. */
    static String facts(ExportDoc doc, int maxRows) {
        StringBuilder b = new StringBuilder("REPORT: ").append(doc.title()).append('\n');
        if (doc.subtitle() != null && !doc.subtitle().isBlank()) {
            b.append("AS OF: ").append(doc.subtitle()).append('\n');
        }
        for (ExportDoc.Section s : doc.sections()) {
            List<Integer> keep = new ArrayList<>();
            for (int c = 0; c < s.columns().size(); c++) {
                if (!s.masked().contains(s.columns().get(c))) {
                    keep.add(c);
                }
            }
            if (s.rows().isEmpty() || keep.isEmpty()) {
                continue;
            }
            b.append("SECTION ").append(s.title()).append('\n');
            if (ExportDoc.CARD.equals(s.type())) {
                for (int c : keep) {
                    b.append("  ").append(s.columns().get(c)).append(": ").append(DocFmt.cell(s.rows().get(0).size() > c ? s.rows().get(0).get(c) : null)).append('\n');
                }
                continue;
            }
            StringBuilder head = new StringBuilder("  ");
            for (int k = 0; k < keep.size(); k++) {
                head.append(k == 0 ? "" : " | ").append(s.columns().get(keep.get(k)));
            }
            b.append(head).append('\n');
            int shown = Math.min(s.rows().size(), maxRows);
            for (int r = 0; r < shown; r++) {
                StringBuilder line = new StringBuilder("  ");
                List<Object> row = s.rows().get(r);
                for (int k = 0; k < keep.size(); k++) {
                    line.append(k == 0 ? "" : " | ").append(DocFmt.cell(keep.get(k) < row.size() ? row.get(keep.get(k)) : null));
                }
                b.append(line).append('\n');
            }
            if (s.rows().size() > shown) {
                b.append("  (").append(s.rows().size() - shown).append(" more rows not shown)\n");
            }
        }
        return b.toString();
    }
}
