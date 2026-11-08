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
/**
 * The outbound HTTP proxy every connector shares (docs/guides/DATA_GUIDE.md section 10): the effective proxy of a connector, from its own block,
 * the site default or the environment ({@link com.ash.drishti.api.proxy.ProxySettings}), and the hosts that go direct
 * ({@link com.ash.drishti.api.proxy.NoProxy}).
 */
package com.ash.drishti.api.proxy;
