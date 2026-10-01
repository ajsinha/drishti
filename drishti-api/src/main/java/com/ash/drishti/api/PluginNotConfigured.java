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
package com.ash.drishti.api;

/**
 * Thrown by {@link SourcePlugin#start} when the plugin has no settings to run with (a Kafka connector with no topics, a
 * feed with no feed). The server then leaves the plugin idle and says so, instead of reporting a failure: an installed
 * plugin nobody configured is not a fault.
 */
public final class PluginNotConfigured extends RuntimeException {

    public PluginNotConfigured(String message) {
        super(message, null, false, false);
    }
}
