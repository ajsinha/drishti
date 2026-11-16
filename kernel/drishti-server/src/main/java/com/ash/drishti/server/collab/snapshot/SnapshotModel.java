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
package com.ash.drishti.server.collab.snapshot;

import java.util.ArrayList;
import java.util.List;

/**
 * What a snapshot draws, as plain data: a canvas size and a list of items (text, boxes, lines, polylines). The layout produces it,
 * the painter rasterises it; everything a picture says is therefore readable from {@link #texts()} before any pixel exists, which is
 * how the rights and the watermark are tested.
 */
public record SnapshotModel(int width, int height, List<Item> items) {

    public SnapshotModel {
        items = List.copyOf(items);
    }

    /** One thing to draw. Colours are {@code 0xRRGGBB}. */
    public sealed interface Item permits Text, Box, Line, Poly {}

    /** A line of text with its baseline at {@code y}; {@code angle} (degrees, 0 = horizontal) and {@code alpha} serve the watermark. */
    public record Text(int x, int y, String text, int size, boolean bold, boolean mono, int color, double angle, float alpha) implements Item {}

    /** A rectangle, filled or outlined. */
    public record Box(int x, int y, int w, int h, int color, boolean fill) implements Item {}

    public record Line(int x1, int y1, int x2, int y2, int color) implements Item {}

    public record Poly(int[] xs, int[] ys, int color) implements Item {}

    /** Every string drawn, in drawing order. */
    public List<String> texts() {
        List<String> out = new ArrayList<>();
        for (Item i : items) {
            if (i instanceof Text t) {
                out.add(t.text());
            }
        }
        return out;
    }

    /** All the text joined, for a contains check. */
    public String allText() {
        return String.join("\n", texts());
    }
}
