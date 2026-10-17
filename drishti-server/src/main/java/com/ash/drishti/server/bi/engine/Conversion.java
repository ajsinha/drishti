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
package com.ash.drishti.server.bi.engine;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.server.bi.dataset.Node;

/** Currency conversion ({@code fx}, {@code convert}): built with the model's FX rates in wave B task 2. */
final class Conversion {

    private Conversion() {}

    static String emit(SqlEmitter e, Node.Call c, boolean inAgg) {
        throw new DrishtiException(ErrorCode.DATASET_NOT_BUILT, "'" + c.function() + "' is not built yet");
    }
}
