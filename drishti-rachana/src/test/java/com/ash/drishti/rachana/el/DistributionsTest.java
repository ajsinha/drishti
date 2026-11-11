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
package com.ash.drishti.rachana.el;

import static com.ash.drishti.rachana.el.Fy.eval;
import static com.ash.drishti.rachana.el.Fy.evalError;
import static com.ash.drishti.rachana.el.Fy.masked;
import static com.ash.drishti.rachana.el.Fy.near;
import static com.ash.drishti.rachana.el.Fy.num;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * The distributions (RACHANA_REFERENCE.md section F9) on the examples of Excel's own documentation (NORM.DIST, NORM.INV, NORM.S.DIST,
 * NORM.S.INV, LOGNORM.DIST, LOGNORM.INV, T.DIST, T.DIST.2T, T.DIST.RT, T.INV, T.INV.2T, CHISQ.DIST, CHISQ.INV, F.DIST, F.INV, BINOM.DIST,
 * BINOM.INV, POISSON.DIST, EXPON.DIST, GAMMA.DIST, GAMMA.INV, GAMMALN, GAMMA, BETA.DIST, BETA.INV, WEIBULL.DIST, HYPGEOM.DIST,
 * NEGBINOM.DIST, CONFIDENCE.NORM, CONFIDENCE.T, STANDARDIZE, FISHER, FISHERINV, ERF), to the digits Excel's pages print; the numerics against
 * identities to near machine precision; and the rules (empty in empty out, masked, a domain error in words).
 */
class DistributionsTest {

    @Test
    void theNormalFamilyMatchesExcelsDocumentedExamples() {
        near("norm_dist(42, 40, 1.5, true)", 0.9087888, 1e-7);                 // NORM.DIST
        near("norm_dist(42, 40, 1.5, false)", 0.10934005, 1e-8);
        near("norm_inv(0.908789, 40, 1.5)", 42.000002, 1e-6);                  // NORM.INV
        near("norm_s_dist(1.333333, true)", 0.908788726, 1e-9);                // NORM.S.DIST
        near("norm_s_dist(1.333333, false)", 0.164010148, 1e-9);
        near("norm_s_dist(1.333333)", 0.908788726, 1e-9);                       // cumulative by default
        near("norm_s_inv(0.908789)", 1.3333347, 1e-7);                          // NORM.S.INV
        near("lognorm_dist(4, 3.5, 1.2, true)", 0.0390836, 1e-7);              // LOGNORM.DIST
        near("lognorm_dist(4, 3.5, 1.2, false)", 0.0176176, 1e-7);
        near("lognorm_inv(0.039084, 3.5, 1.2)", 4.0000252, 1e-6);               // LOGNORM.INV
        near("standardize(42, 40, 1.5)", 1.333333333, 1e-9);                    // STANDARDIZE
        near("confidence_norm(0.05, 2.5, 50)", 0.692951912, 1e-9);              // CONFIDENCE.NORM
        near("confidence_t(0.05, 1, 50)", 0.284196855, 1e-9);                   // CONFIDENCE.T
        near("fisher(0.75)", 0.972955075, 1e-9);                                // FISHER
        near("fisher_inv(0.972955)", 0.75, 1e-7);                               // FISHERINV
        near("erf(1)", 0.842700793, 1e-9);                                      // ERF
        near("erfc(1)", 0.157299207, 1e-9);
    }

    @Test
    void theNormalIsRightToMachinePrecisionAndInTheTails() {
        near("norm_s_inv(0.975)", 1.959963984540054, 1e-14);
        near("norm_s_inv(0.5)", 0, 1e-15);
        near("norm_s_inv(0.01)", -2.326347874040841, 1e-13);
        near("norm_s_inv(1e-10)", -6.361340902404056, 1e-11);
        near("norm_s_dist(-6.361340902404056)", 1e-10, 1e-20);                  // the tail keeps its relative digits
        near("norm_s_dist(norm_s_inv(0.123))", 0.123, 1e-15);
        near("norm_s_dist(-10)", 7.619853024160527e-24, 1e-36);
        near("norm_s_dist(9)", 1, 1e-15);
    }

    @Test
    void studentChiSquareAndFMatchExcel() {
        near("t_dist(60, 1, true)", 0.99469533, 1e-8);                          // T.DIST
        near("t_dist(8, 3, false)", 0.00073691, 1e-8);
        near("2 * (1 - t_dist(1.959999998, 60))", 0.054644930, 1e-9);           // T.DIST.2T
        near("1 - t_dist(1.959999998, 60)", 0.027322465, 1e-9);                 // T.DIST.RT
        near("t_inv(0.75, 2)", 0.8164966, 1e-7);                                // T.INV
        near("t_inv(1 - 0.546449 / 2, 60)", 0.606533, 1e-6);                     // T.INV.2T
        near("t_inv(0.25, 2)", -0.8164966, 1e-7);
        near("t_dist(0.5, 1.9)", num("t_dist(0.5, 1)"), 0);                      // degrees of freedom are cut to whole numbers
        near("chisq_dist(0.5, 1, true)", 0.52049988, 1e-8);                     // CHISQ.DIST
        near("chisq_dist(2, 3, false)", 0.20755375, 1e-8);
        near("chisq_inv(0.93, 1)", 3.283020287, 1e-9);                          // CHISQ.INV
        near("chisq_inv(0.6, 2)", 1.832581464, 1e-9);
        assertThat(eval("chisq_inv(0, 4)")).isEqualTo(0L);
        near("f_dist(15.2069, 6, 4, true)", 0.99, 1e-6);                        // F.DIST
        near("f_dist(15.2069, 6, 4, false)", 0.0012238, 1e-7);
        near("f_inv(0.01, 6, 4)", 0.109309914, 1e-9);                           // F.INV
        near("f_inv(f_dist(3.7, 5, 9), 5, 9)", 3.7, 1e-12);
    }

    @Test
    void discreteDistributionsMatchExcel() {
        near("binom_dist(6, 10, 0.5, false)", 0.205078125, 1e-12);             // BINOM.DIST
        near("binom_dist(6, 10, 0.5, true)", 0.828125, 1e-12);
        assertThat(eval("binom_inv(6, 0.5, 0.75)")).isEqualTo(4L);              // BINOM.INV
        near("poisson_dist(2, 5, true)", 0.124652, 1e-6);                       // POISSON.DIST
        near("poisson_dist(2, 5, false)", 0.084224, 1e-6);
        near("hypgeom_dist(1, 4, 8, 20, true)", 0.4654, 1e-4);                  // HYPGEOM.DIST
        near("hypgeom_dist(1, 4, 8, 20, false)", 0.3633, 1e-4);
        near("negbinom_dist(10, 5, 0.25, true)", 0.3135141, 1e-7);              // NEGBINOM.DIST
        near("negbinom_dist(10, 5, 0.25, false)", 0.0550487, 1e-7);
        evalError("binom_dist(11, 10, 0.5)", "binom_dist", "more than the trials");
        evalError("binom_dist(1, 10, 1.5)", "binom_dist", "not from 0 to 1");
    }

    @Test
    void continuousShapesMatchExcel() {
        near("expon_dist(0.2, 10, true)", 0.86466472, 1e-8);                    // EXPON.DIST
        near("expon_dist(0.2, 10, false)", 1.35335283, 1e-8);
        near("gamma_dist(10.00001131, 9, 2, false)", 0.032639, 1e-6);           // GAMMA.DIST
        near("gamma_dist(10.00001131, 9, 2, true)", 0.068094, 1e-6);
        near("gamma_inv(0.068094, 9, 2)", 10.0000112, 1e-5);                    // GAMMA.INV
        near("gamma_ln(4)", 1.791759469, 1e-9);                                 // GAMMALN
        near("gamma(2.5)", 1.329340388, 1e-9);                                  // GAMMA
        near("gamma(-0.75)", -4.834146, 1e-6);
        assertThat(eval("gamma(5)")).isEqualTo(24L);
        near("beta_dist(2, 8, 10, true, 1, 3)", 0.6854706, 1e-7);               // BETA.DIST
        near("beta_dist(2, 8, 10, false, 1, 3)", 1.4837646, 1e-7);
        near("beta_inv(0.685470581, 8, 10, 1, 3)", 2, 1e-8);                     // BETA.INV
        near("weibull_dist(105, 20, 100, true)", 0.929581, 1e-6);              // WEIBULL.DIST
        near("weibull_dist(105, 20, 100, false)", 0.035589, 1e-6);
    }

    @Test
    void emptyMaskedWrongTypeAndOutOfDomain() {
        assertThat(eval("norm_s_dist($.NOPE)")).isNull();
        assertThat(eval("norm_dist(1, $.NOPE, 1)")).isNull();
        assertThat(eval("t_inv(0.9, $.NOPE)")).isNull();
        assertThat(masked("norm_s_inv($.MTM)")).isTrue();
        assertThat(masked("chisq_dist(1, 2, $.MTM)")).isTrue();
        evalError("norm_s_dist('abc')", "norm_s_dist", "'abc' is not a number");
        evalError("norm_dist(1, 0, 0)", "norm_dist", "standard deviation", "not above zero");
        evalError("norm_s_inv(1)", "norm_s_inv", "not between 0 and 1");
        evalError("norm_s_inv(0)", "not between 0 and 1");
        evalError("t_dist(1, 0.5)", "t_dist", "degrees of freedom");
        evalError("chisq_dist(-1, 2)", "chisq_dist", "negative");
        evalError("chisq_dist(0, 1, false)", "chisq_dist", "infinite");
        evalError("gamma(-2)", "gamma", "negative whole number");
        evalError("gamma_ln(0)", "gamma_ln", "not above zero");
        evalError("fisher(1)", "fisher", "between −1 and 1");
        evalError("confidence_t(0.05, 1, 1)", "confidence_t", "under 2");
        evalError("beta_dist(5, 8, 10, true, 1, 3)", "beta_dist", "not within A to B");
        assertThat(eval("iferror(norm_s_inv(2), -1)")).isEqualTo(-1L);
        assertThat(num("norm_s_dist('1.5')")).isCloseTo(0.933192799, within(1e-9));   // number-like text is a number
    }
}
