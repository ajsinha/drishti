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
package com.ash.drishti.engine.shape;

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;

/** Input the shape extractor refuses: over a configured limit (DRS-5005). */
public final class ShapeException extends DrishtiException {

    public ShapeException(String message) {
        super(ErrorCode.PAYLOAD_TOO_LARGE, message);
    }
}
