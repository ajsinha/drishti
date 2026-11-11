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
package com.ash.drishti.tables.answer;

import com.ash.drishti.api.DataNode;
import com.ash.drishti.rachana.el.EvalContext;
import com.ash.drishti.rachana.el.Expr;
import com.ash.drishti.rachana.el.Values;
import com.ash.drishti.tables.def.ColumnDef;
import com.ash.drishti.tables.def.TableDef;
import com.ash.drishti.tables.lang.Trees;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What one reader may see of one table (HOW_IT_FITS.md §A28 Q9; HOW_IT_FITS.md section A10, V9): the table's row rules for the
 * reader's roles (a rule written over the source's paths is read over the table's columns), the table hidden from a role, the columns
 * hidden from a role, and the masked columns for a reader without the raw power. One place for every reader of a table: a model's answer
 * ({@link ModelAnswers}), a direct read ({@code tables.read.TableReads}), and through them the pivot, Sutras and Flight. A table with no
 * rules is open to every reader of its source; a table with rules is closed to a role none of them names.
 */
public final class TableAccess {

    private static final java.util.regex.Pattern RELATED_ROWS = java.util.regex.Pattern.compile("\\brelated_rows\\s*\\(");

    private final TableDef table;
    private final List<Expr> rules;
    private final boolean all;
    private final boolean none;
    private final Set<String> masks;
    private final Set<String> hidden;
    private final DataNode user;

    private TableAccess(TableDef table, List<Expr> rules, boolean all, boolean none, Set<String> masks, Set<String> hidden, DataNode user) {
        this.table = table;
        this.rules = rules;
        this.all = all;
        this.none = none;
        this.masks = masks;
        this.hidden = hidden;
        this.user = user;
    }

    /** The reader's access to {@code t}. */
    public static TableAccess of(TableDef t, ModelAnswers.Reader who) {
        return of(t, who, false);
    }

    /**
     * The reader's access to {@code t}; {@code maySeeAll} (a model's {@code may-see-all} for the reader) lifts the table's row rules, never
     * its masks.
     */
    public static TableAccess of(TableDef t, ModelAnswers.Reader who, boolean maySeeAll) {
        List<Expr> rules = new ArrayList<>();
        boolean named = t.rules().isEmpty();
        for (TableDef.Rule r : t.rules()) {
            if (!Collections.disjoint(r.roles(), who.roles())) {
                named = true;
                // related_rows('alias') reads another table of a model: only BI's engine can honour it, so read here it lets no row through
                rules.add(onColumns(Trees.parse(RELATED_ROWS.matcher(r.where()).find() ? "false" : r.where()), t));
            }
        }
        boolean hiddenTable = !Collections.disjoint(t.hiddenFor(), who.roles());
        Set<String> masks = new LinkedHashSet<>();
        Set<String> hidden = new LinkedHashSet<>();
        for (ColumnDef c : t.columns().values()) {
            if (!who.raw() && c.masked()) {
                masks.add(c.name());
            }
            if (!Collections.disjoint(c.hiddenFor(), who.roles())) {
                hidden.add(c.name());
            }
        }
        boolean none = hiddenTable || !named && !maySeeAll;
        return new TableAccess(t, rules, maySeeAll || rules.isEmpty(), none, masks, hidden,
                DataNode.of(new LinkedHashMap<>(who.attributes())));
    }

    /** True when the reader sees none of the table's rows (no rule names their roles, or the table is hidden from one of them). */
    public boolean none() {
        return none;
    }

    /** The columns the reader sees masked. */
    public Set<String> masks() {
        return masks;
    }

    /** The columns hidden from the reader (left out of what they read). */
    public Set<String> hidden() {
        return hidden;
    }

    /** The reader's attributes as {@code user} in a rule. */
    public DataNode user() {
        return user;
    }

    /** True when a row (its columns by name) passes the reader's rules. */
    public boolean allows(DataNode row) {
        if (none) {
            return false;
        }
        if (all) {
            return true;
        }
        Map<String, DataNode> fields = new LinkedHashMap<>(((DataNode.Obj) row).fields());
        fields.put("user", user);
        DataNode withUser = new DataNode.Obj(fields);
        return rules.stream().anyMatch(r -> Values.truthy(r.eval(EvalContext.of(withUser, null))));
    }

    /** The row as the reader sees it: masked columns as the mask, hidden columns left out. */
    public DataNode shown(DataNode row) {
        if (masks.isEmpty() && hidden.isEmpty()) {
            return row;
        }
        Map<String, DataNode> kept = new LinkedHashMap<>(((DataNode.Obj) row).fields());
        masks.forEach(c -> kept.put(c, DataNode.masked()));
        hidden.forEach(kept::remove);
        return new DataNode.Obj(kept);
    }

    /** The table this access is for. */
    public TableDef table() {
        return table;
    }

    /** A table rule written over the source's paths, read over the table's rows: each path that a column reads becomes that column. */
    static Expr onColumns(Expr rule, TableDef t) {
        Map<String, String> byPath = new LinkedHashMap<>();
        t.columns().values().stream().filter(c -> c.path() != null).forEach(c -> byPath.putIfAbsent(c.path(), c.name()));
        return Trees.map(rule, n -> {
            String name = Trees.name(n);
            if (name != null && byPath.containsKey(name)) {
                return new Expr.Field(new Expr.RowOrRoot(), byPath.get(name), null);
            }
            return n;
        });
    }
}
