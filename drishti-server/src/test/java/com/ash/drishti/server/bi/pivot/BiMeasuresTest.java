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
package com.ash.drishti.server.bi.pivot;

import com.ash.drishti.server.bi.Layered;
import static org.assertj.core.api.Assertions.assertThat;

import com.ash.drishti.bicompute.formula.FormulaFunction;
import com.ash.drishti.kernel.api.pivot.Batch;
import com.ash.drishti.kernel.api.pivot.Kind;
import com.ash.drishti.server.bi.dataset.Dataset;
import com.ash.drishti.server.bi.engine.TestLake;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A model's measures as pivot functions (HOW_IT_FITS.md section A26.9): DEL's aggregates, {@code ratio}, {@code where=}, {@code count(table)} and
 * {@code measure('Name')} translate to the engine's formulas with the right kind and additivity, and evaluate to the same figure as by hand.
 */
class BiMeasuresTest {

    final Dataset ds = Layered.dataset(TestLake.yaml()).dataset();

    @Test
    void theTestLakesMeasuresArePivotFunctions() {
        for (String m : List.of("Notional", "MTM", "Trades", "AvgMTM", "MTMperM", "Names", "P50", "EurMTM", "Biggest", "Share", "NotionalM")) {
            assertThat(BiMeasures.why(ds, m)).as(m).isNull();
            assertThat(BiMeasures.translate(ds, m).tables()).as(m).containsExactly("trades");
        }
        assertThat(BiMeasures.additive(ds, "Notional")).isTrue();
        assertThat(BiMeasures.additive(ds, "Trades")).isTrue();
        assertThat(BiMeasures.additive(ds, "EurMTM")).as("a sum with a condition still adds up").isTrue();
        assertThat(BiMeasures.additive(ds, "AvgMTM")).isFalse();
        assertThat(BiMeasures.additive(ds, "Share")).isFalse();
        assertThat(BiMeasures.translate(ds, "Trades").formula()).isEqualTo("count()");
        assertThat(FormulaFunction.of("P50", BiMeasures.translate(ds, "P50").formula()).kind()).isEqualTo(Kind.HOLISTIC);
        assertThat(BiMeasures.why(ds, "Nope")).contains("no measure");
    }

    @Test
    void aTranslatedMeasureComputesWhatItsDelSays() {
        BiMeasures.Translated share = BiMeasures.translate(ds, "Share");      // measure('MTM') / measure('Notional')
        List<String> in = new ArrayList<>(share.fields());
        FormulaFunction f = FormulaFunction.of("Share", share.formula(), in);
        double[] mtm = {10, -4, 6};
        double[] notional = {100, 200, 300};
        Object[] cols = new Object[in.size()];
        for (int i = 0; i < in.size(); i++) {
            cols[i] = in.get(i).endsWith("mtm") ? mtm : notional;
        }
        assertThat(f.finish(f.add(f.start(), new Batch(3, cols)))).isEqualTo(12.0 / 600.0, org.assertj.core.data.Offset.offset(1e-15));
    }
}
