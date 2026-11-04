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
package com.ash.drishti.server.exposure;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.bicompute.core.PivotView;
import com.ash.drishti.server.pivot.PivotViews;
import com.ash.drishti.server.security.Principal;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CCR lens of the pivot engine, {@code /api/v1/pivot/ccr} (EXPOSURE_GUIDE.md section 30.5; engine {@code ccr-cube}: off, every path
 * answers {@code 404 DRS-1020} from the engines' gate). The grid is the pivot engine's own {@code POST /api/v1/pivot/query} on source
 * {@code ccr}; these are what the CCR lens draws beside it, as the person:
 * <pre>
 *   POST /api/v1/pivot/ccr/headline  {view}                              landing A's headline: peak PFE, EPE, EEPE, over and near limit, the run
 *   GET  /api/v1/pivot/ccr/fan?scope=&amp;q=&amp;collateral=&amp;asOf=   a scope's EE and PFE by date with the limit's step line and headroom
 * </pre>
 * Tab B (a netting set) and tab C (pre-deal, SA-CCR, FVA, the group) read the engines' own APIs ({@code /api/v1/exposure},
 * {@code /api/v1/saccr}, {@code /api/v1/fva}); a {@code view} is the view itself or a saved pivot view's id ({@code owner/name}).
 */
@RestController
@RequestMapping("/api/v1/pivot/ccr")
public class CcrCubeController {

    private final CcrCubeExtras extras;
    private final PivotViews views;

    CcrCubeController(CcrCubeExtras extras, PivotViews views) {
        this.extras = extras;
        this.views = views;
    }

    @SuppressWarnings("unchecked")
    private PivotView view(Principal p, Map<String, Object> body) {
        Object v = body == null ? null : body.get("view");
        if (v instanceof String id) {
            return views.open(p, id);
        }
        if (v instanceof Map<?, ?> m) {
            return PivotView.parse((Map<String, Object>) m);
        }
        throw PivotView.bad("the CCR lens needs the view: {\"view\": {\"source\": \"ccr\", ...}} or a saved view's id");
    }

    @PostMapping("/headline")
    public Map<String, Object> headline(@RequestAttribute(Principal.ATTRIBUTE) Principal p, @RequestBody Map<String, Object> body) {
        return extras.headline(p, view(p, body));
    }

    @GetMapping("/fan")
    public Map<String, Object> fan(@RequestParam(required = false) String scope, @RequestParam(required = false) String q,
            @RequestParam(required = false) String collateral, AsOf asOf, @RequestAttribute(Principal.ATTRIBUTE) Principal p) {
        return extras.fan(p, scope, asOf == null ? null : asOf.businessDate(), q, collateral);
    }
}
