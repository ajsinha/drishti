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
package com.ash.drishti.kernel.api.module;

import com.ash.drishti.api.AsOf;
import com.ash.drishti.api.EntityDocument;
import com.ash.drishti.api.EntityRef;
import com.ash.drishti.api.Subscription;
import com.ash.drishti.kernel.api.data.BindingState;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What the kernel gives a started module (docs/architecture/KERNEL_AND_MODULES.md section 2.3): its kinds through the kernel's bindings,
 * its settings, its data folder, the audit log and the business date. There is no way to a connector's settings or a secret: a module
 * names kinds, the site binds them (section 5.10).
 */
public interface ModuleContext {

    /** The module this context belongs to. */
    String moduleId();

    /** The kinds the module reads, bound by the kernel ({@code config/bindings.yaml}). */
    Kinds kinds();

    /** Where the module's produced kinds are written (the kind's {@code write:} binding); the module never names a path. */
    KindWriter writer();

    /** The module's settings, validated against its schema. */
    ModuleSettings settings();

    /** The module's own folder under the data folder, for state files and caches. */
    Path dataDir();

    /** Records an entry in the kernel's audit log, as the module. */
    void audit(String action, Map<String, Object> detail);

    /** Today's business date. */
    LocalDate businessDate();

    /** Reads of bound kinds: rows, never the connection. */
    interface Kinds {

        /** One entity on a business date. */
        Optional<EntityDocument> fetch(EntityRef ref, AsOf asOf);

        /** Every document of the kind on the date, to {@code sink}; false when the kind is not bound. */
        boolean documents(String kind, AsOf asOf, Consumer<EntityDocument> sink);

        /** Changes of one entity from the kind's {@code live:} source. */
        Subscription subscribe(EntityRef ref, Consumer<EntityDocument> listener);

        /** How the kind is bound now. */
        BindingState state(String kind);
    }

    /** Writes of a produced kind to its bound target. */
    interface KindWriter {

        /** Writes {@code rows} of {@code kind} for {@code date}; refused when the kind has no write binding. */
        void write(String kind, LocalDate date, List<Map<String, Object>> rows);
    }
}
