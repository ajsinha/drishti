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
package com.ash.drishti.engine.view;

import com.ash.drishti.rachana.model.Panel;
import com.ash.drishti.rachana.model.ScreenPhone;
import com.ash.drishti.rachana.model.Sutra;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a Sutra says about the phone (docs/guides/PACK_DEVELOPER_GUIDE.md sections 4 and 5), as the view model carries it: each top-level panel's tier and
 * compact options with the layer that set them ({@code from: fragment} or {@code sutra}), the screen's phone order and default tier, and
 * the summary line read against the document. The console adds the kind defaults, the site's, pack's and edition's settings and caps,
 * resolves the tier per panel and draws the phone page. Pure.
 */
public final class PhoneView {

    private PhoneView() {}

    /**
     * The view's {@code phone}, or null when the Sutra says nothing about the phone (the console then uses the kind defaults).
     *
     * @param summary the screen's summary line already read against the document, or null
     */
    public static Map<String, Object> of(Sutra s, String summary) {
        if (s == null) {
            return null;
        }
        Map<String, Object> panels = new LinkedHashMap<>();
        for (Panel p : s.panels()) {
            if (p.options().get(ScreenPhone.KEY) instanceof Map<?, ?> m) {
                panels.put(p.id(), m);
            }
        }
        Map<String, Object> out = s.phone() == null ? new LinkedHashMap<>() : s.phone().toView();
        if (!panels.isEmpty()) {
            out.put("panels", panels);
        }
        if (summary != null && !summary.isBlank()) {
            out.put("summary", summary);
        }
        return out.isEmpty() ? null : out;
    }
}
