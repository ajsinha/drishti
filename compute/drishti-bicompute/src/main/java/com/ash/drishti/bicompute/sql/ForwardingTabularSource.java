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
package com.ash.drishti.bicompute.sql;

import com.ash.drishti.kernel.api.pivot.PivotProvider;
import java.util.List;

/** A tabular source that wraps another: what it shows of the relation is the wrapped one's, and the subclass changes how it is read. */
public abstract class ForwardingTabularSource implements TabularSource {

    private final TabularSource base;

    protected ForwardingTabularSource(TabularSource base) {
        this.base = base;
    }

    @Override
    public String id() {
        return base.id();
    }

    @Override
    public String title() {
        return base.title();
    }

    @Override
    public List<PivotProvider.Field> fields() {
        return base.fields();
    }

    @Override
    public int masked() {
        return base.masked();
    }

    @Override
    public String date() {
        return base.date();
    }

    @Override
    public List<String> dates() {
        return base.dates();
    }
}
