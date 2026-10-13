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

import com.ash.drishti.common.DrishtiException;
import com.ash.drishti.common.ErrorCode;
import com.ash.drishti.identity.bi.BiStore;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One model per name in a scope (docs/architecture/BI_MODEL_SPEC.md section 2.1): a model is referred to by its name ({@code trading-risk@1}), so two model
 * items of one name in a workspace, or two published in the catalogue, would make a reference ambiguous. A save, a move and a publish that would make a
 * second one are refused with {@code DRS-9004}, naming the item that holds the name.
 */
public final class ModelNames {

    private static final Pattern NAME = Pattern.compile("(?m)^model:\\s*[\"']?([A-Za-z0-9_.-]+)[\"']?\\s*(?:#.*)?$");

    private ModelNames() {}

    /** The {@code model:} name a model's text declares, or null. */
    public static String of(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = NAME.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /** Refuses a model item {@code id} with {@code body} in {@code workspace} when another model item of that workspace declares the same name. */
    public static void requireUniqueInWorkspace(Collection<BiStore.Item> items, String workspace, String id, String kind, String body) {
        String name = "model".equals(kind) ? of(body) : null;
        if (name == null) {
            return;
        }
        for (BiStore.Item other : items) {
            if ("model".equals(other.kind()) && !other.id().equals(id) && other.workspace().equals(workspace) && name.equals(of(other.body()))) {
                throw new DrishtiException(ErrorCode.BI_CONFLICT, "this workspace already has a model named '" + name + "' ('" + other.title() + "', " + other.id()
                        + "): a model is referred to by its name, so give this one another `model:` name, or edit that one");
            }
        }
    }

    /** Refuses publishing a model when another published model (stored or shipped) already holds its name in the catalogue. */
    public static void requireUniqueInCatalogue(Collection<BiStore.Item> published, String id, String body, java.util.function.Function<BiStore.Item, String> bodyOf) {
        String name = of(body);
        if (name == null) {
            return;
        }
        for (BiStore.Item other : published) {
            if ("model".equals(other.kind()) && !other.id().equals(id) && name.equals(of(bodyOf.apply(other)))) {
                throw new DrishtiException(ErrorCode.BI_CONFLICT, "the catalogue already has a published model named '" + name + "' ('" + other.title() + "', " + other.id()
                        + "): a model is referred to by its name, so publish this one under another `model:` name, or publish a new version of that one");
            }
        }
    }
}
