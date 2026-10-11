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
package com.ash.drishti.server.bi;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Publishing and governance, {@code drishti.bi.governance.*} (docs/admin/CONFIGURATION.md, docs/guides/BI_GOVERNANCE.md): how many saved states an item
 * keeps, what a reviewer is shown, what a publish may break, what happens to certification, bundles, and the notifications.
 *
 * @param revisionsKeep saved states (revisions) kept per item; the oldest go first, but a revision that is a published version stays
 * @param previewRows rows of data a reviewer sees in the preview of a dataset or a report under review (the reviewer's own row rules and masks apply)
 * @param refuseBreaking refuse to publish a dataset change that removes a field, measure or hierarchy a published report uses, unless a bi-admin forces it
 * @param revokeCertification take certification away automatically from a certified item whose dataset changed incompatibly (and from a certified
 *     dataset that changed incompatibly itself)
 * @param bundleMaxBytes largest BI bundle that is imported
 * @param tellPeople tell reviewers and authors in the inbox (the bell) when a review is asked for, decided, or a certification is revoked
 */
@ConfigurationProperties("drishti.bi.governance")
public record BiGovProperties(Integer revisionsKeep, Integer previewRows, Boolean refuseBreaking, Boolean revokeCertification, Integer bundleMaxBytes, Boolean tellPeople) {

    public BiGovProperties {
        revisionsKeep = revisionsKeep == null || revisionsKeep <= 1 ? 200 : revisionsKeep;
        previewRows = previewRows == null || previewRows <= 0 ? 20 : Math.min(previewRows, 500);
        refuseBreaking = refuseBreaking == null || refuseBreaking;
        revokeCertification = revokeCertification == null || revokeCertification;
        bundleMaxBytes = bundleMaxBytes == null || bundleMaxBytes <= 0 ? 8_388_608 : bundleMaxBytes;
        tellPeople = tellPeople == null || tellPeople;
    }

    public static BiGovProperties defaults() {
        return new BiGovProperties(null, null, null, null, null, null);
    }
}
