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
package com.ash.drishti.server.bi.layer;

/** Which item bodies are the table layer's: checked by the layer when saved and published, kept as typed (HOW_IT_FITS.md). */
public final class LayerBodies {

    private LayerBodies() {}

    /** True for a table or a model of the table layer ({@code rachana: 3}). */
    public static boolean tableLayer(String kind, String body) {
        return ("table".equals(kind) || "model".equals(kind)) && body != null && body.lines().anyMatch(l -> l.strip().equals("rachana: 3"));
    }
}
