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

import com.ash.drishti.kernel.api.pivot.AggFunction;
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
 * One grammar, one engine (docs/architecture/ARCHITECTURE.md section 5), as rules over the compiled classes:
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
     * The expression parsers outside Rachana that are allowed, each for a stated reason and only until the release named. DEL, BI's dataset
     * expressions and the pivot's formulas are Rachana since 2.9.11.d; what is left: the migration tool's reader of DEL (it reads the texts it
     * rewrites, never evaluates them) and the VaR scope selector.
     */
    static final Map<String, String> EXPRESSION_PARSER_EXCEPTIONS = Map.of(
            "com.ash.drishti.server.bi.expr.DelParser", "the migration tool's reader of DEL (drishti.py tables migrate and the stored items' "
                    + "migration at start): it reads the old texts it rewrites into Rachana, never evaluates them; deleted with the migration tool in "
                    + "2.9.14 (docs/architecture/ARCHITECTURE.md §28 Q24)",
            "com.ash.drishti.banking.compute.var.ScopeExpr", "the VaR scope selector (VAR_GUIDE.md 5.2): moves in its own change with VaR's "
                    + "exact-equality regression check (docs/architecture/ARCHITECTURE.md §28 Q20)");

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
            .because("every aggregate over values is the pivot engine's (docs/architecture/ARCHITECTURE.md section 5): use its SPI through PivotCore, a "
                    + "provider's nativeFunction made by the engine, or Rachana's aggregates, which the engine registers");

    @ArchTest
    static final ArchRule onlyTheEngineAddsFunctionsToRachana = noClasses()
            .that().resideOutsideOfPackage("com.ash.drishti.bicompute..")
            .should().implement(FunctionProvider.class)
            .orShould().implement(FunctionProvider.Grouping.class)
            .because("Rachana's aggregates, grouping, measure and cell come from the pivot engine only (docs/architecture/ARCHITECTURE.md parts 1 to 3)");

    @ArchTest
    static final ArchRule rachanaNeverDependsOnTheEngine = noClasses()
            .that().resideInAPackage("com.ash.drishti.rachana..")
            .should().dependOnClassesThat().resideInAPackage("com.ash.drishti.bicompute..")
            .because("the engine depends on Rachana for paths and expressions, never the reverse (docs/architecture/ARCHITECTURE.md section 2)");

    @ArchTest
    static final ArchRule onlyRachanaParsesExpressions = classes()
            .that(PARSES_EXPRESSIONS).and(DescribedPredicate.not(LISTED_EXCEPTION))
            .should().resideInAPackage("com.ash.drishti.rachana..")
            .allowEmptyShould(true)
            .because("Rachana is the one expression language (docs/architecture/ARCHITECTURE.md section 5); the migration's DEL reader and the VaR scope "
                    + "selector are the listed, temporary exceptions");
}
