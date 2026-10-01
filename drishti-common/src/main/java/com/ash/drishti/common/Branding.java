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
package com.ash.drishti.common;

/**
 * The product's name and legal notices, from configuration ({@code drishti.branding.*}) so nothing user-visible names
 * the product or its owner in code. Empty when not configured.
 *
 * @param product the product name shown to people ({@code Drishti})
 * @param tagline one line under the name
 * @param owner who holds the rights
 * @param copyright the copyright line shown on pages and in the About answer
 * @param notice the restriction shown beside it ({@code Unauthorised copying … is prohibited.})
 */
public record Branding(String product, String tagline, String owner, String copyright, String notice) {

    public Branding {
        product = product == null ? "" : product;
        tagline = tagline == null ? "" : tagline;
        owner = owner == null ? "" : owner;
        copyright = copyright == null ? "" : copyright;
        notice = notice == null ? "" : notice;
    }

    /** The product name, or {@code fallback} when none is configured. */
    public String productOr(String fallback) {
        return product.isBlank() ? fallback : product;
    }
}
