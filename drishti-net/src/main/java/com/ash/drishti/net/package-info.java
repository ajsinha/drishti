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
/**
 * Drishti*net: several Drishti servers acting as one (docs/architecture/DRISHTI_NET.md). Membership by SWIM-style gossip
 * over HTTP ({@code member}), the cluster key and on-behalf-of tokens ({@code security}), what each node offers
 * ({@code digest}), where a request goes ({@code route}), the {@code config/net/} folder ({@code config}) and the signed
 * HTTP calls between nodes ({@code transport}). Nothing here knows Spring, the engine or the server: the server wires it in.
 */
package com.ash.drishti.net;
