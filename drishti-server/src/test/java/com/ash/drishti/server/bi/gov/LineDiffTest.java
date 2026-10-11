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
package com.ash.drishti.server.bi.gov;

import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.server.bi.dataset.DatasetParser;
import org.junit.jupiter.api.Test;

class LineDiffTest {

    @Test
    void identicalTextsHaveNoChanges() {
        LineDiff.Result r = LineDiff.diff("a: 1\nb: 2\n", "a: 1\nb: 2\n");
        assertThat(r.identical()).isTrue();
        assertThat(r.rows()).hasSize(2).allMatch(x -> x.op().equals("same"));
    }

    @Test
    void aChangedLineIsPairedAndAddedAndRemovedLinesStandAlone() {
        LineDiff.Result r = LineDiff.diff("a: 1\nb: 2\nc: 3\n", "a: 1\nb: 9\nc: 3\nd: 4\n");
        assertThat(r.changed()).isEqualTo(1);
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.removed()).isZero();
        assertThat(r.rows().get(1).left()).isEqualTo("b: 2");
        assertThat(r.rows().get(1).right()).isEqualTo("b: 9");
        assertThat(r.rows().get(3).op()).isEqualTo("added");
        assertThat(r.rows().get(3).rightNo()).isEqualTo(4);
        LineDiff.Result gone = LineDiff.diff("a\nb\nc\n", "a\nc\n");
        assertThat(gone.removed()).isEqualTo(1);
        assertThat(gone.rows().get(1).left()).isEqualTo("b");
    }

    @Test
    void anEmptySideIsAllAdded() {
        LineDiff.Result r = LineDiff.diff("", "x\ny\n");
        assertThat(r.added()).isEqualTo(2);
        assertThat(LineDiff.diff(null, "").identical()).isTrue();
    }

    @Test
    void aFieldRemovedFromADatasetIsFoundByName() {
        String before = "dataset: d\ntables:\n  t:\n    source: { kind: trade }\n    columns: { a: string, b: string }\nmeasures:\n  N: { expr: \"count(t)\" }\n";
        String after = before.replace("b: string", "c: string");
        Surface.Change c = Surface.change(Surface.Offer.of(DatasetParser.parse(before).dataset()), Surface.Offer.of(DatasetParser.parse(after).dataset()));
        assertThat(c.breaking()).isTrue();
        assertThat(c.removedFields()).containsExactly("t.b");
        assertThat(c.addedFields()).containsExactly("t.c");
        assertThat(c.removedMeasures()).isEmpty();
        assertThat(Surface.change(Surface.Offer.of(DatasetParser.parse(after).dataset()), Surface.Offer.of(DatasetParser.parse(before).dataset())).removedFields()).containsExactly("t.c");
    }
}
