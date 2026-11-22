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
 * Drishti hosts engines (docs/architecture/ENGINES_IN_DRISHTI.md; docs/plans/ENGINE_INTEGRATION_DESIGN.md sections 0, 5 and 11): the engine
 * JARs of {@code <home>/lib/engines} opened through drishti-engine's {@code engine-host}, bound from {@code config/engines/<id>.yaml}, fed
 * through {@link com.ash.drishti.enginehost.KindData} (the read API the router serves) with the caller's row rules and masks applied, and
 * answered, stored and published by the host. The server wires the ports ({@link com.ash.drishti.enginehost.KindData},
 * {@link com.ash.drishti.enginehost.ViewerAccess}, {@link com.ash.drishti.enginehost.WhereCompiler},
 * {@link com.ash.drishti.enginehost.KindContracts}); nothing here knows Spring.
 */
package com.ash.drishti.enginehost;
