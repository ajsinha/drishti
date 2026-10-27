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
package com.ash.drishti.server.var;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * One record's fields against its payload, its id and its system's pin (VAR_GUIDE.md section 15.13): every field required; a pin is a
 * cross-check (agreeing passes, disagreeing is {@code DRS-5209}); {@code default} pins the systems not listed; {@code any} accepts; a pin
 * changed (the settings of the next start) applies to what is read from then on; a bad pin is refused at start.
 */
class VarInputsTest {

    private static VarInputs with(Map<String, VarProperties.Input> pins) {
        Map<String, VarProperties.Input> up = new java.util.HashMap<>();
        pins.forEach((k, v) -> up.put(k.toUpperCase(java.util.Locale.ROOT), v));
        return new VarInputs(s -> up.getOrDefault(s.toUpperCase(java.util.Locale.ROOT), up.getOrDefault("DEFAULT", new VarProperties.Input(null, null))));
    }

    @Test
    void everyFieldIsRequiredAndMustMatchThePayloadAndTheId() {
        VarInputs any = with(Map.of());
        assertThat(any.check("PV", "PV", "TRADE", "T1", null, "ENDUR", "EUR")).isNull();
        assertThat(any.check("PNL", "PNL", "POSITION", null, "P1", "MUREX", "USD")).isNull();
        assertThat(any.check("PV", null, "TRADE", "T1", null, "ENDUR", "EUR")[1]).startsWith("no VALUE_TYPE");
        assertThat(any.check("PV", "PV", null, "T1", null, null, null)[1]).startsWith("no GRAIN, CURRENCY, SOURCE_SYSTEM");
        assertThat(any.check("PNL", "PV", "TRADE", "T1", null, "MUREX", "USD")[0]).isEqualTo("DRS-5208");
        assertThat(any.check("PV", "PV", "POSITION", "T1", null, "ENDUR", "EUR")[1]).startsWith("GRAIN POSITION without a POSITION_ID");
        assertThat(any.check("PV", "PV", "TRADE", null, null, "ENDUR", "EUR")[1]).startsWith("GRAIN TRADE without a TRADE_ID");
        assertThat(any.check("PV", "PV", "LOT", "T1", null, "ENDUR", "EUR")[1]).contains("not TRADE or POSITION");
    }

    @Test
    void aPinIsACrossCheckOnlyItsDefaultCoversTheRest() {
        VarInputs pinned = with(Map.of("ENDUR", new VarProperties.Input("pv", "position"), "default", new VarProperties.Input("pnl", null)));
        assertThat(pinned.check("PV", "PV", "POSITION", null, "P1", "ENDUR", "JPY")).isNull();                  // field and pin agree
        String[] off = pinned.check("PV", "PV", "TRADE", "T1", null, "ENDUR", "JPY");                            // they disagree
        assertThat(off[0]).isEqualTo("DRS-5209");
        assertThat(off[1]).isEqualTo("ENDUR is pinned to pv, position (drishti.var.inputs); the record says pv, trade");
        assertThat(pinned.check("PNL", "PNL", "TRADE", "MX-1", null, "MUREX", "USD")).isNull();                // default: pnl, any grain
        assertThat(pinned.check("PV", "PV", "TRADE", "C-1", null, "CALYPSO", "GBP")[0]).isEqualTo("DRS-5209");
        // a pin changed (the next start's settings): the same record now passes, from the next date read
        VarInputs changed = with(Map.of("ENDUR", new VarProperties.Input("pv", "trade")));
        assertThat(changed.check("PV", "PV", "TRADE", "T1", null, "ENDUR", "JPY")).isNull();
        assertThatThrownBy(() -> new VarProperties.Input("vectors", null)).hasMessageContaining("drishti.var.inputs: form vectors");
    }

    @Test
    void oneSystemOneFormAndGrainADate() {
        VarInputs in = with(Map.of());
        in.saw("MUREX", VarInputs.combo("PNL", "TRADE"));
        in.saw("ENDUR", VarInputs.combo("PV", "POSITION"));
        assertThat(in.mixed()).isEmpty();
        assertThat(in.describe().get("ENDUR")).containsEntry("form", "pv").containsEntry("grain", "position").containsEntry("decidedBy", "record fields");
        assertThat(in.describe().get("MUREX")).containsEntry("tradesWithoutPv", 1);
        in.saw("MUREX", VarInputs.combo("PNL", "POSITION"));
        assertThat(in.mixed()).containsExactly("MUREX sent {PNL/POSITION=1, PNL/TRADE=1}");
    }
}
