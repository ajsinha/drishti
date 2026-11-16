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
package com.ash.drishti.server.liveset;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

/** The columns a bare mnemonic shows from a kind's contract: a date column only when the data has one. */
class ContractColumnsTest {
    private static java.util.List<String> columns(String yaml) throws Exception {
        return LiveSetConfiguration.columnsOf(new ObjectMapper(new YAMLFactory()).readTree(yaml));
    }

    @Test
    void aKindDatedByBusinessDateShowsItsBusinessDate() throws Exception {
        assertThat(columns("key: [CURVE_ID]\ndated-by: business_date\nfields:\n- name: CURVE_ID\n- name: TEN_Y\n"))
                .containsExactly("CURVE_ID", "business_date", "TEN_Y");
    }

    @Test
    void aKindDatedByASnapshotHasNoDateColumn() throws Exception {
        assertThat(columns("key: [NEWS_ID]\ndated-by: snapshot\nfields:\n- name: NEWS_ID\n- name: ENTITY\n"))
                .containsExactly("NEWS_ID", "ENTITY");
    }

    @Test
    void aKindDatedByOneOfItsFieldsShowsThatField() throws Exception {
        assertThat(columns("key: [ID]\ndated-by: AS_OF\nfields:\n- name: ID\n- name: AS_OF\n")).containsExactly("ID", "AS_OF");
    }
}
