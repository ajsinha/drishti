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
package com.ash.drishti.registry;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The one place every layer asks (ACCESS_MODEL.md section 2): licence → module → lens → permission. The server at start (which modules to
 * load), every API call, data (packs, connectors), the console through the server, assets, Help, AI tools, exports, embeds and tokens.
 * Nothing else reads the access model (an architecture test). Embedded in the server now ({@link EmbeddedAccessRegistry}); a remote
 * registry later behind the same interface. Every answer is cheap: decisions are cached and recomputed when the model changes.
 */
public interface AccessRegistry {

    /** Every module known here, the core first, with its state. */
    List<ModuleState> modules();

    /** One module's state; empty for a module not known here. */
    Optional<ModuleState> module(String id);

    /** True when the module runs here: installed ∧ licensed ∧ enabled, and every module it requires on. The core is always on. */
    boolean moduleOn(String id);

    /** The manifest of a module known here. */
    Optional<ModuleManifest> manifest(String id);

    /** The module whose code holds the class (by its package roots); empty for the core's. */
    Optional<String> moduleOfClass(String className);

    /** False for a class of a module that is off (a connector plugin, an engine class named in a file). */
    default boolean classOn(String className) {
        return moduleOfClass(className).map(this::moduleOn).orElse(true);
    }

    /** The module that brings the compute engine (the engine catalogue's id); empty for the core's engines. */
    Optional<String> moduleOfEngine(String engineId);

    /** The module that answers the API path; empty for the core's. */
    Optional<String> moduleOfApi(String path);

    /** The module that owns the connector (by name); empty for the core's and the packs' own. */
    Optional<String> moduleOfConnector(String name);

    /**
     * True when a pack loads here: the module that lists it must be on; otherwise its domain must be the core's, or a module of that
     * domain must be on. A domain no module here declares loads as the core's (with a warning in {@link #warnings()}).
     *
     * @param packId the pack's id
     * @param domain the pack's {@code domain:} (blank: the core)
     */
    boolean packOn(String packId, String domain);

    /** The functions of the modules that are on (command words a module brings), the core's not listed. */
    Set<String> words();

    /** The module the function (a command word, any case) belongs to; empty for the core's (a word no module declares). */
    Optional<String> moduleOfFunction(String word);

    /** The function whose APIs hold the path; empty when no function declares it. */
    Optional<String> functionOfApi(String path);

    /**
     * True when the function is on here: its module is on and no administrator switched it off (the mnemonic switch, Admin → Functions,
     * asked through {@link #functionSwitches}).
     */
    boolean functionOn(String word);

    /** Why the function is off, in words; empty when it is on. */
    Optional<String> functionOffReason(String word);

    /**
     * True when the person may use the function: it is on, and (from 2.9.11.i) a role of theirs grants it through a lens's bundle or a
     * single grant, and none denies it. Until then every person may use every function that is on.
     */
    boolean functionAllowed(Subject subject, String word);

    /**
     * Where the administrator's installation-level function switches are kept (the switches ledger, Admin → Functions: gossiped across the
     * cluster); the registry asks it and decides. {@code on(word)} is null when no switch was set.
     */
    void functionSwitches(FunctionSwitches switches);

    /** The administrator's installation-level function switches, as the registry reads them. */
    @FunctionalInterface
    interface FunctionSwitches {

        /** True or false when an administrator set the function's switch; null when not set (on). */
        Boolean on(String word);

        /** No switch set anywhere. */
        FunctionSwitches NONE = w -> null;
    }

    /** The lenses offered here: those whose modules are all on. */
    List<Lens> lenses();

    /** True when the model declares the lens (offered or not: its modules may be off). */
    boolean lensDeclared(String id);

    /** True when the lens is offered here. */
    boolean lensOffered(String id);

    /** The offered lenses this person may open (every offered lens when the access model declares no roles, or for an administrator). */
    List<Lens> lensesFor(Subject subject);

    /** True when the person may do the action in the lens (the lens must be offered and open to them). */
    boolean allowed(Subject subject, String lens, String action);

    /** The licence in force. */
    Licence licence();

    /** What could not be read or decided, in words (never fatal). */
    List<String> warnings();

    /**
     * Sets the administrator's switch of a module. Takes effect at the next start: a module's beans, engines, jobs and API are loaded
     * when the server starts.
     */
    void setEnabled(String module, boolean on, String by);

    /** A value that changes whenever a decision may have changed. */
    long version();

    /** Calls {@code listener} after the model changed and the decisions were recomputed; closing the handle removes it. */
    AutoCloseable onChange(Runnable listener);

    /** Where the model is kept, in words. */
    String describe();
}
