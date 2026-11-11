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
package com.ash.drishti.rachana.design.ops;

import com.ash.drishti.rachana.SutraException;
import com.ash.drishti.rachana.SutraProblem;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies a list of {@link Op operations} to the text of a Sutra, one after the other. After each one the result is parsed
 * as a Sutra; an operation that fails, or whose result is not valid, is skipped with a located {@link OpProblem} (its index
 * in the list) and the text stays as it was, so the output is never a corrupted Sutra. The text of a valid input keeps its
 * comments, quoting and key order wherever an operation does not touch it. Stateless and thread-safe.
 */
public final class OpApplier {

    /**
     * @param yaml the Sutra text; it may be invalid only when the first operation is a {@link Text} that replaces it
     * @param ops the operations, in order
     */
    public OpResult apply(String yaml, List<? extends Op> ops) {
        String text = yaml == null ? "" : yaml;
        List<OpProblem> problems = new ArrayList<>();
        int applied = 0;
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            try {
                SutraDoc doc = new SutraDoc(text);
                op.applyTo(doc);
                String next = doc.text();
                doc.sutra();                                     // the result must be a valid Sutra
                text = next;
                applied++;
            } catch (OpException e) {
                problems.add(new OpProblem(i, op.name(), e.code(), e.getMessage(), e.line()));
            } catch (SutraException e) {
                SutraProblem first = e.problems().get(0);
                String more = e.problems().size() > 1 ? " (and " + (e.problems().size() - 1) + " more)" : "";
                int line = first.location() == null ? 0 : first.location().line();
                problems.add(new OpProblem(i, op.name(), first.code(), first.message() + more, line));
            } catch (RuntimeException e) {
                problems.add(new OpProblem(i, op.name(), OpException.UNEDITABLE, "cannot apply: " + e.getMessage(), 0));
            }
        }
        return new OpResult(text, List.copyOf(problems), applied);
    }
}
