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
package com.ash.drishti.plugin.news;

import java.time.Instant;

/**
 * One normalised headline.
 *
 * @param title the headline, without the trailing " - Source" Google adds
 * @param source the publisher
 * @param link the article address (http or https only)
 * @param published when it was published, or null when the feed gave no usable date
 * @param snippet a short plain-text summary, or empty
 */
public record NewsItem(String title, String source, String link, Instant published, String snippet) {}
