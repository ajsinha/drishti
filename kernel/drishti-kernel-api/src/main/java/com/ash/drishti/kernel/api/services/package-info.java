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
 * The typed kernel API (docs/architecture/KERNEL_AND_MODULES.md section 2.3), kernel API {@code 0.5}: what a module calls instead of the
 * loopback HTTP calls the landings made to their own server. {@link com.ash.drishti.kernel.api.services.Kernel} is one bean (also
 * {@code ModuleContext.kernel()}); each of its services answers exactly what the REST path it replaces answers, for the same
 * {@link com.ash.drishti.kernel.api.services.Person}, through the same doors (entitlements, masks, row rules, lens, date box, token scopes).
 *
 * <p>Two tiers. Kernel services are Java interfaces here. One module asks another through
 * {@link com.ash.drishti.kernel.api.services.Operations}: the provider marks methods {@link com.ash.drishti.kernel.api.services.Operation},
 * the consumer writes its own interface with the same names; no class is shared between modules, and a call follows placement to the node
 * that holds the provider's part.
 */
package com.ash.drishti.kernel.api.services;
