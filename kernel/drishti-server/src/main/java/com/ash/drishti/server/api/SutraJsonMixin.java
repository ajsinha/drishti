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
package com.ash.drishti.server.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.ash.rachana.model.Match;
import java.util.Map;
import org.springframework.boot.jackson.JsonMixin;

/**
 * A Sutra as the API has always written it: {@code domain} and {@code match} at the top, in their old places, and none of the profile's
 * extension map (Core's record keeps a profile's keys there; its entries are not all data: the problem places of a parse are not).
 */
@JsonMixin(com.ash.rachana.model.Sutra.class)
@JsonPropertyOrder({"name", "version", "domain", "match", "title", "strip", "panels", "keys", "location", "description", "controls", "layout", "phone"})
abstract class SutraJsonMixin {

    @JsonIgnore
    abstract Map<String, Object> extensions();

    @JsonProperty("domain")
    abstract String domain();

    @JsonProperty("match")
    abstract Match match();
}
