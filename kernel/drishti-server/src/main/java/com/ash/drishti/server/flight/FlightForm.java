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
package com.ash.drishti.server.flight;

import com.ash.drishti.flight.FlightBackend;
import com.ash.drishti.flight.FlightRequest;
import com.ash.drishti.kernel.api.security.Principal;
import java.util.List;

/**
 * One form of Flight request answered by the part of the server that already answers it over REST (docs/guides/FLIGHT_GUIDE.md):
 * kinds by the Data view's reader, models by MQ, measures by their engines, the in-memory lake by its kinds. A form reads as the person
 * ({@link Principal}: the token's user, or the person acted for), so the entitlements, masks and audit of that part apply unchanged.
 */
public interface FlightForm {

    FlightRequest.Form form();

    /** The domain whose {@code data:read:<domain>} scope opens the request (the kind's pack, the model's pack, the measure's engine). */
    String domain(Principal p, FlightRequest r);

    /** The schema and the parts; the parts' nodes are filled by the backend when the form leaves them empty (this node). */
    FlightBackend.Plan plan(Principal p, FlightRequest r, FlightNodes nodes);

    FlightBackend.BatchSource open(Principal p, FlightRequest r, int part, int batchRows);

    /** A live stream; a form without one refuses with the reason. */
    FlightBackend.Subscription subscribe(Principal p, FlightRequest r, int part, int batchRows);

    /** What this person may read in this form, as requests (names only), for ListFlights. */
    List<FlightRequest> catalogue(Principal p);
}
