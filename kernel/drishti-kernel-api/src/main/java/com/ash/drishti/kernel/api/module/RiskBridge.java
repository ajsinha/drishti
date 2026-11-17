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

package com.ash.drishti.kernel.api.module;

import java.util.Map;

/**
 * Temporary bridge of the trading landings' {@code /risk/*} calls (the VaR and exposure engines of the banking module) until they are
 * typed operations ({@code Kernel.operations()}, TODO(I13)): the call as the person, through the server's own doors. The server
 * implements it; a module never builds an HTTP call of its own. Deleted with the last such call.
 */
public interface RiskBridge {

    /**
     * {@code GET /api/v1<path>} as the person; a JSON object's fields.
     *
     * @throws com.ash.drishti.kernel.api.services.Kernel.Refused the server's refusal, with its status, code and words
     */
    Map<String, Object> get(com.ash.drishti.kernel.api.services.Person person, String path, Map<String, ?> params);

    /** {@code POST /api/v1<path>} with a JSON body, as the person; as {@link #get}. */
    Map<String, Object> post(com.ash.drishti.kernel.api.services.Person person, String path, Object body, Map<String, ?> params);
}
