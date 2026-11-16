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
package com.ash.drishti.banking;

import com.ash.drishti.server.modules.ConditionalOnModule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Banking's own tests' harness, and only that: the module's configuration in the test server's context, as the module host loads it in a
 * server (docs/architecture/KERNEL_AND_MODULES.md section 3.3), and only while the site's switch says the module is on, as the host does.
 * A server never loads banking this way: the full distribution installs {@code banking-1.0.0.dmod}.
 */
@AutoConfiguration
@ConditionalOnModule("banking")
@Import(BankingAutoConfiguration.class)
public class BankingTestHarness {
}
