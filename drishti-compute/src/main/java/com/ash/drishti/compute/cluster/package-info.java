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
 * Python compute across the cluster's nodes (docs/guides/NOTEBOOK_GUIDE.md section 7.6): the other nodes serving a pool as
 * fan-out sees them ({@link com.ash.drishti.compute.cluster.PeerTransport}) and one request split into chunks run on every worker there is
 * ({@link com.ash.drishti.compute.cluster.FanOut}).
 */
package com.ash.drishti.compute.cluster;
