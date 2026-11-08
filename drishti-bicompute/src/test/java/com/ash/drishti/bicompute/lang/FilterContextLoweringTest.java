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
package com.ash.drishti.bicompute.lang;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ash.drishti.bicompute.context.FilterContext;
import com.ash.drishti.bicompute.context.FilterContext.Edit;
import com.ash.drishti.bicompute.context.FilterContext.Field;
import com.ash.drishti.bicompute.context.FilterContext.Filter;
import com.ash.drishti.bicompute.context.FilterContext.Kind;
import com.ash.drishti.bicompute.context.FilterContext.Question;
import com.ash.drishti.bicompute.context.Periods;
import com.ash.drishti.common.BusinessCalendar;
import com.ash.drishti.rachana.el.CheckedMode;
import com.ash.drishti.rachana.el.check.ElType;
import com.ash.drishti.rachana.el.check.Fields;
import com.ash.drishti.rachana.el.check.Problem;
import com.ash.drishti.rachana.el.check.Scope;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The filter-context functions in the engine (BI_DESIGN.md 3.3.5, carried over by docs/architecture/BI_DESIGN.md 2.3): each Rachana form of
 * the design's examples lowered to its context sub-plan, the edited question the engine decides for it, and the rules 1 to 7.
 */
class FilterContextLoweringTest {

    /** A model: desks.NAME, books.NAME, trades.MTM, trades.TRADE_DATE, trades.STATUS; trades.DESK masked. */
    static final Fields MODEL = new Fields() {
        @Override
        public Optional<Field> field(List<String> parts) {
            String p = String.join(".", parts);
            return switch (p) {
                case "desks.NAME", "books.NAME", "trades.STATUS" -> Optional.of(new Field(p, ElType.STRING, false));
                case "trades.MTM" -> Optional.of(new Field(p, ElType.DECIMAL, false));
                case "trades.TRADE_DATE" -> Optional.of(new Field(p, ElType.DATE, false));
                case "trades.DESK" -> Optional.of(new Field(p, ElType.STRING, true));
                default -> Optional.empty();
            };
        }

        @Override
        public boolean alias(String name) {
            return List.of("trades", "desks", "books").contains(name);
        }

        @Override
        public Optional<String> measure(String nameOrId) {
            return "MTM".equals(nameOrId) ? Optional.of("MTM") : Optional.empty();
        }
    };

    static final Compiler C = new Compiler(parts -> String.join(".", parts), SqlLowering.Options.of(LocalDate.of(2026, 10, 16)));

    static Lowered lower(String text) {
        assertThat(CheckedMode.check(text, Scope.AGGREGATE, MODEL).problems().stream().filter(Problem::error)).as(text).isEmpty();
        return C.measure(CheckedMode.parse(text).expr());
    }

    /** The answer: grouped by desk, book and the masked trades.DESK; filtered by a desk slicer, a status, and a measure. */
    static final List<Field> ANSWER = List.of(new Field("desks.NAME", "desks.NAME", false), new Field("books.NAME", "books.NAME", false),
            new Field("trades.DESK", "trades.DESK", true));
    static final List<Filter> FILTERS = List.of(new Filter("desks.NAME", false), new Filter("trades.STATUS", false), new Filter(null, true));

    @Test
    void calcIgnoreDropsTheSlicerAndTheGroupOfThoseFieldsOnly() {
        Lowered.Context c = (Lowered.Context) lower("calc(measure('MTM'), ignore = [desks.NAME])");
        assertThat(c.edit().kind()).isEqualTo(Kind.EDIT);
        assertThat(c.edit().ignore()).containsExactly("desks.NAME");
        Question q = FilterContext.plan(c.edit(), ANSWER, FILTERS);
        assertThat(q.groups()).containsExactly("books.NAME");                    // the masked trades.DESK is never a group (rule of masks)
        assertThat(q.on()).containsExactly("books.NAME");
        assertThat(q.filters()).containsExactly(1);                              // the status stays; the measure filter never reaches a context
    }

    @Test
    void calcWhereIsTheMeasuresOwnLayerAndEditsNothing() {
        Lowered.Context c = (Lowered.Context) lower("calc(sum(trades.MTM), where = trades.STATUS != 'MATURED')");
        assertThat(c.edit().edits()).isFalse();
        assertThat(c.where().sql()).isEqualTo("(trades.STATUS IS DISTINCT FROM 'MATURED')");    // the null rule (Q1)
        Question q = FilterContext.plan(c.edit(), ANSWER, FILTERS);
        assertThat(q.groups()).containsExactly("desks.NAME", "books.NAME");
        assertThat(q.filters()).containsExactly(0, 1);
    }

    @Test
    void keepIsIgnoreOfTheComplementAndViaFollowsAnotherRelationship() {
        Lowered.Context k = (Lowered.Context) lower("calc(measure('MTM'), keep = [books.NAME])");
        assertThat(FilterContext.plan(k.edit(), ANSWER, FILTERS).groups()).containsExactly("books.NAME");
        assertThat(FilterContext.plan(k.edit(), ANSWER, FILTERS).filters()).isEmpty();
        Lowered.Context v = (Lowered.Context) lower("calc(measure('MTM'), via = 'r_5')");
        assertThat(v.edit().via()).isEqualTo("r_5");
        assertThat(v.edit().edits()).isTrue();
        assertThat(FilterContext.plan(v.edit(), ANSWER, FILTERS).groups()).containsExactly("desks.NAME", "books.NAME");
    }

    /** Rule 1: security is never in scope; ignore = ALL is everything the reader may see (the plan has no access to rules at all). */
    @Test
    void ignoreAllDropsEveryQueryAndGroupFilterButNeverSecurity() {
        Lowered.Shape s = (Lowered.Shape) lower("share_of(measure('MTM'), over = ALL)");
        assertThat(s.sql()).isEqualTo("(CAST({{h0}} AS DOUBLE) / NULLIF(CAST({{h1}} AS DOUBLE), 0))");
        Lowered.Context total = (Lowered.Context) s.parts().get(1);
        assertThat(total.edit().all()).isTrue();
        Question q = FilterContext.plan(total.edit(), ANSWER, FILTERS);
        assertThat(q.groups()).isEmpty();
        assertThat(q.filters()).isEmpty();
        assertThat(FilterContext.join("SELECT 1", "\"c0_0\"", List.of(), List.of())).endsWith("ON TRUE");
        assertThat(FilterContext.read(Kind.EDIT, "\"c0_0\".\"__ctx\"")).isEqualTo("max(\"c0_0\".\"__ctx\")");
    }

    @Test
    void shareOfWithinTheDeskAndParentOfAHierarchy() {
        Lowered.Shape s = (Lowered.Shape) lower("share_of(measure('MTM'), over = [books.NAME])");
        assertThat(((Lowered.Context) s.parts().get(1)).edit().ignore()).containsExactly("books.NAME");
        Lowered.Context p = (Lowered.Context) lower("parent_of(measure('MTM'), hierarchy = 'Desk and book')");
        assertThat(p.hierarchy()).isEqualTo("Desk and book");
        assertThat(FilterContext.parentLevel(List.of(true, true))).isEqualTo(1);       // a book: its desk is the parent
        assertThat(FilterContext.parentLevel(List.of(true, false))).isEqualTo(-1);     // a desk, the top: no parent
        assertThat(FilterContext.parentLevel(List.of(false, false))).isEqualTo(-1);    // no level in the answer
    }

    /** Rule 3: no context transition; avg_by iterates explicitly, at the coarser grain of the answer plus its by fields. */
    @Test
    void averageByIsExplicitIteration() {
        Lowered.Context a = (Lowered.Context) lower("avg_by(sum(trades.MTM), by = [trades.STATUS])");
        assertThat(a.edit().kind()).isEqualTo(Kind.AVERAGE);
        Question q = FilterContext.plan(a.edit(), ANSWER, FILTERS);
        assertThat(q.groups()).containsExactly("desks.NAME", "books.NAME", "trades.STATUS");
        assertThat(q.on()).containsExactly("desks.NAME", "books.NAME");
        assertThat(q.filters()).containsExactly(0, 1);
        assertThat(FilterContext.average("SELECT …", List.of("\"desks.NAME\""), "\"__ctx\"")).isEqualTo(
                "SELECT \"desks.NAME\", avg(\"__ctx\") AS \"__ctx\" FROM (SELECT …) AS \"a\" GROUP BY \"desks.NAME\"");
        assertThat(((Lowered.Context) lower("avg_over(sum(trades.MTM), trades.TRADE_DATE)")).edit().by()).containsExactly("trades.TRADE_DATE");
        assertThat(CheckedMode.check("sum(sum(trades.MTM))", Scope.AGGREGATE, MODEL).firstError().orElseThrow().message())
                .contains("an aggregate inside an aggregate");
    }

    /** Rule 2: nesting at most three deep, in the checker (written) and in the engine (through the measures named). */
    @Test
    void nestingIsAtMostThreeDeep() {
        assertThat(CheckedMode.check("calc(calc(calc(measure('MTM'), ignore = ALL), ignore = ALL), ignore = ALL)", Scope.AGGREGATE, MODEL).ok()).isTrue();
        assertThat(CheckedMode.check("calc(calc(calc(calc(measure('MTM'), ignore = ALL), ignore = ALL), ignore = ALL), ignore = ALL)",
                Scope.AGGREGATE, MODEL).firstError().orElseThrow().message()).contains("nest at most 3 deep");
        FilterContext.checkDepth(2);
        assertThatThrownBy(() -> FilterContext.checkDepth(3)).hasMessageContaining("nest more than 3 deep");
    }

    /** Rule 7: as_of's date is worked out before the query (never from a row); a live table has no other dates. */
    @Test
    void asOfIsBoundToOneDateBeforeTheQueryRuns() {
        Lowered.Shape s = (Lowered.Shape) lower("measure('MTM') - as_of(measure('MTM'), shift_date(asof(), -1))");
        Lowered.Context day = (Lowered.Context) s.parts().get(1);
        assertThat(day.edit().kind()).isEqualTo(Kind.DATED);
        assertThat(day.edit().date()).isEqualTo(LocalDate.of(2026, 10, 15));
        Question q = FilterContext.plan(day.edit(), ANSWER, FILTERS);
        assertThat(q.groups()).containsExactly("desks.NAME", "books.NAME");     // only the date changes
        assertThatThrownBy(() -> C.measure(CheckedMode.parse("as_of(measure('MTM'), trades.TRADE_DATE)").expr()))
                .hasMessageContaining("DRS-9166");
        assertThat(((Lowered.Context) lower("as_of(sum(trades.MTM), date('2026-09-30'))")).edit().date()).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    /** Rule 6: a calculation-group item is a template in the same syntax; its parameters bind as param.name. */
    @Test
    void templatesAndParametersAreTheSameSyntax() {
        assertThat(CheckedMode.parse("ytd(SELECTED, trades.TRADE_DATE)").expr()).isNotNull();
        Lowered.Time t = (Lowered.Time) lower("ytd(sum(trades.MTM), trades.TRADE_DATE)");
        assertThat(t.date()).isEqualTo("trades.TRADE_DATE");
        assertThat(((Lowered.Time) lower("closing(sum(trades.MTM), trades.TRADE_DATE, period = 'quarter')")).period()).isEqualTo("quarter");
        assertThat(((Lowered.Time) lower("latest(sum(trades.MTM), trades.TRADE_DATE)")).fn()).isEqualTo("latest");
        Lowered.Window w = (Lowered.Window) lower("rank(sum(trades.MTM), desc = false, by = [desks.NAME])");
        assertThat(w.desc()).isFalse();
        assertThat(w.by()).containsExactly("desks.NAME");
        Lowered.Window lag = (Lowered.Window) lower("lag(sum(trades.MTM), 1, trades.TRADE_DATE)");
        assertThat(lag.n()).isEqualTo(1);
        assertThat(lag.order()).isEqualTo("trades.TRADE_DATE");
        assertThat(lower("convert(sum(trades.MTM), to = 'EUR')")).isEqualTo(new Lowered.Convert(lower("sum(trades.MTM)"), "EUR", "as-of"));
    }

    @Test
    void theBalancesDates() {
        BusinessCalendar cal = BusinessCalendar.of("WEEKENDS");
        assertThat(Periods.closingDays(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 7, 1), "month", cal)).containsExactly(
                LocalDate.of(2026, 7, 31), LocalDate.of(2026, 8, 31), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 16));
        assertThat(Periods.openingDay(LocalDate.of(2026, 10, 16), "month", cal)).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(Periods.openingDay(LocalDate.of(2026, 10, 16), "quarter", cal)).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThatThrownBy(() -> Periods.period("closing", "fortnight")).hasMessageContaining("period is one of");
        assertThat(FilterContext.key(new Edit(Kind.EDIT, true, Set.of(), null, null, List.of(), null), "sum(x)",
                new Question(List.of(), List.of(), List.of()), List.of())).isEqualTo("EDIT|sum(x)|[]|[]|null|null");
    }
}
