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
package com.ash.drishti.server.compute;

import com.ash.drishti.api.compute.Viewer;
import com.ash.drishti.server.security.Principal;

/**
 * The reactive connectors' viewer of a principal (user, roles, the hash of their rights), for a service that asks a computed kind itself
 * (the exposure engine's exact what-if asks the Python engine as the person who asked it).
 */
public final class Viewers {

    private Viewers() {}

    public static Viewer of(Principal p) {
        return ReactiveHostImpl.viewerOf(p);
    }
}
