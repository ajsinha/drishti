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
package com.ash.drishti.engine.view;

import com.ash.drishti.api.DataNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Counts, in what a view shows, the values a caller's field masks replaced ({@link DataNode#MASK}): the number behind
 * {@code provenance.masked}, and which panels hold some. Counted on the built view (the text a person would read), so it holds
 * for every panel kind without each binder reporting its own.
 */
public final class MaskCount {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** How many masked values, and which panels hold some. */
    public record Result(int count, List<String> panels) {}

    private MaskCount() {}

    /** Counts over the strip cells, the title and every panel's content. */
    public static Result of(ViewModel.TitleView title, List<ViewModel.Cell> strip, List<ViewModel.PanelView> panels) {
        int total = 0;
        if (title != null) {
            total += text(title.pill()) + text(title.id()) + (title.with() == null ? 0 : text(title.with().text()));
        }
        for (ViewModel.Cell c : strip) {
            total += text(c.text());
        }
        List<String> withMasks = new ArrayList<>();
        for (ViewModel.PanelView p : panels) {
            int n = p.data() == null ? 0 : count(JSON.valueToTree(p.data()));
            if (n > 0) {
                total += n;
                withMasks.add(p.id());
            }
        }
        return new Result(total, withMasks);
    }

    private static int text(String s) {
        return s != null && s.contains(DataNode.MASK) ? 1 : 0;
    }

    private static int count(JsonNode n) {
        if (n.isTextual()) {
            return n.asText().contains(DataNode.MASK) ? 1 : 0;
        }
        int sum = 0;
        for (JsonNode child : n) {
            sum += count(child);
        }
        return sum;
    }
}
