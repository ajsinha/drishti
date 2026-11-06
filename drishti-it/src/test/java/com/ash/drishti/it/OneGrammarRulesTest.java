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
package com.ash.drishti.it;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.ash.drishti.bicompute.spi.AggFunction;
import com.ash.drishti.rachana.el.FunctionProvider;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Map;

/**
 * One grammar, one engine (docs/architecture/ONE_GRAMMAR.md section 5), as rules over the compiled classes:
 *
 * <ul>
 *   <li>every aggregate over values is the pivot engine's: no class outside {@code drishti-bicompute} implements its aggregation SPI
 *       ({@link AggFunction}: start, add, merge, finish), and no class outside it adds functions to Rachana's table
 *       ({@link FunctionProvider}); a compute engine's measure reaches the engine through its pivot provider's
 *       {@code nativeFunction}, made by the engine ({@code Final.function}), never implemented beside it;</li>
 *   <li>Rachana never depends on the engine (the engine depends on Rachana);</li>
 *   <li>no module other than {@code drishti-rachana} parses an expression: a class that reads an expression language (a parser or
 *       lexer of expressions or formulas, or an expression type with its own {@code parse}) lives in {@code drishti-rachana}, or is one
 *       of the exceptions listed in {@link #EXPRESSION_PARSER_EXCEPTIONS} with its reason and the release it goes in.</li>
 * </ul>
 */
@AnalyzeClasses(packages = "com.ash.drishti", importOptions = ImportOption.DoNotIncludeTests.class)
class OneGrammarRulesTest {

    /**
     * The expression parsers outside Rachana that are allowed, each for a stated reason and only until the release named: DEL and the
     * BI expressions written in its syntax fold into Rachana in 2.9.11.e (ONE_GRAMMAR.md part 4), with their parity proof, and are then
     * deleted with this list's entries.
     */
    static final Map<String, String> EXPRESSION_PARSER_EXCEPTIONS = Map.of(
            "com.ash.drishti.server.bi.expr.", "DEL, BI's expression language: its checker is Rachana's checked mode since 2.9.11.e part A; "
                    + "deleted with the migration in part B (BI_FOUNDATION_2911E.md 2.6)",
            "com.ash.drishti.server.bi.dataset.ExprParser", "BI dataset expressions (DEL's operators): fold with DEL in 2.9.11.e",
            "com.ash.drishti.server.bi.layer.CalculationParser", "BI model calculations in DEL: fold with DEL in 2.9.11.e",
            "com.ash.drishti.bicompute.formula.", "pivot formulas of built-ins in DEL's syntax (PIVOT_ENGINE.md 8.2): fold with DEL in 2.9.11.e",
            "com.ash.drishti.compute.var.ScopeExpr", "the VaR scope selector (VAR_GUIDE.md 5.2): moves in its own change with VaR's "
                    + "exact-equality regression check, after 2.9.11.e (BI_FOUNDATION_2911E.md Q20)");

    /** A class that reads an expression language: a parser or lexer of expressions or formulas, or an expression type with a static parse. */
    static final DescribedPredicate<JavaClass> PARSES_EXPRESSIONS = DescribedPredicate.describe("parse an expression language", c -> {
        String name = c.getSimpleName();
        String pkg = c.getPackageName();
        boolean parser = name.endsWith("Parser") || name.endsWith("Lexer");
        boolean expressionish = name.matches(".*(Expr|Expression|Formula|Calculation).*");
        boolean expressionPackage = pkg.matches(".*\\.(expr|el|formula)(\\..*)?");
        boolean parses = c.getMethods().stream().anyMatch(m -> m.getName().equals("parse") && m.getModifiers().contains(JavaModifier.STATIC));
        return parser && (expressionish || expressionPackage) || expressionish && parses;
    });

    static final DescribedPredicate<JavaClass> LISTED_EXCEPTION = DescribedPredicate.describe("listed in EXPRESSION_PARSER_EXCEPTIONS",
            c -> EXPRESSION_PARSER_EXCEPTIONS.keySet().stream().anyMatch(k -> k.endsWith(".")
                    ? c.getName().startsWith(k) : c.getName().equals(k) || c.getName().startsWith(k + "$")));

    @ArchTest
    static final ArchRule onlyTheEngineImplementsAnAggregate = noClasses()
            .that().resideOutsideOfPackage("com.ash.drishti.bicompute..")
            .should().implement(AggFunction.class)
            .because("every aggregate over values is the pivot engine's (ONE_GRAMMAR.md section 5): use its SPI through PivotCore, a "
                    + "provider's nativeFunction made by the engine, or Rachana's aggregates, which the engine registers");

    @ArchTest
    static final ArchRule onlyTheEngineAddsFunctionsToRachana = noClasses()
            .that().resideOutsideOfPackage("com.ash.drishti.bicompute..")
            .should().implement(FunctionProvider.class)
            .orShould().implement(FunctionProvider.Grouping.class)
            .because("Rachana's aggregates, grouping, measure and cell come from the pivot engine only (ONE_GRAMMAR.md parts 1 to 3)");

    @ArchTest
    static final ArchRule rachanaNeverDependsOnTheEngine = noClasses()
            .that().resideInAPackage("com.ash.drishti.rachana..")
            .should().dependOnClassesThat().resideInAPackage("com.ash.drishti.bicompute..")
            .because("the engine depends on Rachana for paths and expressions, never the reverse (ONE_GRAMMAR.md section 2)");

    @ArchTest
    static final ArchRule onlyRachanaParsesExpressions = classes()
            .that(PARSES_EXPRESSIONS).and(DescribedPredicate.not(LISTED_EXCEPTION))
            .should().resideInAPackage("com.ash.drishti.rachana..")
            .allowEmptyShould(true)
            .because("Rachana is the one expression language (ONE_GRAMMAR.md section 5); DEL and its kin are the listed, temporary "
                    + "exceptions until 2.9.11.e");
}
