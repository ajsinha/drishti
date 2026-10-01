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
package com.ash.drishti.server.layout;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Personal layouts (USER_GUIDE.md, Layout mode): each user may arrange a view's panels (order, column, width, height,
 * hidden) and keep the arrangement as an overlay on the Sutra, which never changes for anyone else.
 *
 * @param enabled layout mode is offered at all (to roles with {@code layout}); off: nobody sees it and its endpoints refuse
 */
@ConfigurationProperties("drishti.layouts")
public record LayoutProperties(Boolean enabled) {

    public LayoutProperties {
        enabled = enabled == null || enabled;
    }

    /** Registers the settings. */
    @Configuration
    @EnableConfigurationProperties(LayoutProperties.class)
    public static class LayoutConfiguration {}
}
