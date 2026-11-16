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

import com.ash.drishti.server.DrishtiApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Import;

/**
 * The server the banking module's tests start: the whole application (the core's DrishtiApplication, with this module found through its
 * auto-configuration). A {@code @SpringBootTest} under {@code com.ash.drishti.banking} finds it by its package, as the server's own tests
 * find DrishtiApplication.
 */
@SpringBootConfiguration
@Import(DrishtiApplication.class)
public class BankingTestServer {
}
