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
 * The TLS every connector shares (docs/guides/DATA_GUIDE.md section 10): the {@code tls.*} settings ({@link com.ash.drishti.api.tls.TlsSettings}),
 * the SSL context built from PEM, PKCS12 or JKS ({@link com.ash.drishti.api.tls.TlsContexts}, {@link com.ash.drishti.api.tls.TlsMaterial}),
 * HTTP clients ({@link com.ash.drishti.api.tls.TlsHttp}), secrets from files and placeholders ({@link com.ash.drishti.api.tls.Secrets}) and
 * certificate expiry ({@link com.ash.drishti.api.tls.CertInfo}).
 */
package com.ash.drishti.api.tls;
