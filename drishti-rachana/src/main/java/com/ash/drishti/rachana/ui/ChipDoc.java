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
package com.ash.drishti.rachana.ui;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One chip version (UI_BOARD.md section 2): the header, the manual, the pins and the Rachana body. Read by
 * {@link UiDocuments#readChip}; YAML and JSON give equal values.
 *
 * @param rachana the grammar version (1)
 * @param chip the name
 * @param version the version, a positive integer
 * @param title the palette title
 * @param category the palette category
 * @param tags the palette tags
 * @param compatibleWith earlier versions whose boards may move to this one unchanged
 * @param manual the technical manual
 * @param inPins the data and control in pins, in document order
 * @param outPins the out pins, in document order
 * @param limits the chip's own limits ({@code rows}, ...), as written
 * @param body the Rachana body ({@code panels:}, or {@code board:} for a module)
 */
public record ChipDoc(int rachana, String chip, int version, String title, String category, List<String> tags, List<Integer> compatibleWith,
        ChipManual manual, Map<String, PinSpec> inPins, Map<String, PinSpec> outPins, Map<String, JsonNode> limits, JsonNode body) {

    public ChipDoc {
        tags = tags == null ? List.of() : List.copyOf(tags);
        compatibleWith = compatibleWith == null ? List.of() : List.copyOf(compatibleWith);
        inPins = inPins == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(inPins));
        outPins = outPins == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(outPins));
        limits = limits == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(limits));
    }

    /** {@code name@version}. */
    public ChipRef ref() {
        return new ChipRef(chip, version);
    }

    /** An in pin by name. */
    public Optional<PinSpec> in(String pin) {
        return Optional.ofNullable(inPins.get(pin));
    }

    /** An out pin by name. */
    public Optional<PinSpec> out(String pin) {
        return Optional.ofNullable(outPins.get(pin));
    }
}
