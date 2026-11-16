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
package org.helixlab.genomics;

import com.ash.drishti.kernel.api.function.FunctionProvider;
import java.util.List;

/** The Rachana function the module adds while it is loaded: {@code genomics_af_class(af)}, a variant's frequency class. */
public class GenomicsFunctions implements FunctionProvider {

    @Override
    public String source() {
        return "the genomics module";
    }

    @Override
    public List<Provided> functions() {
        return List.of(new Provided("genomics_af_class", 1, 1, "af", "a variant's allele frequency class: rare (< 1%), low (< 5%) or common",
                args -> afClass(args.get(0))));
    }

    /** rare below 1 %, low below 5 %, else common; null for no number. */
    static String afClass(Object af) {
        double v;
        if (af instanceof Number n) {
            v = n.doubleValue();
        } else {
            try {
                v = Double.parseDouble(String.valueOf(af));
            } catch (NumberFormatException | NullPointerException e) {
                return null;
            }
        }
        if (Double.isNaN(v)) {
            return null;
        }
        return v < 0.01 ? "rare" : v < 0.05 ? "low" : "common";
    }
}
