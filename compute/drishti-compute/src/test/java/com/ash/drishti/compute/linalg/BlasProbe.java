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
package com.ash.drishti.compute.linalg;

/** A JVM of its own for {@link LinearAlgebraTest}: binds from the system properties, multiplies once, prints what answered. */
public final class BlasProbe {

    private BlasProbe() {}

    public static void main(String[] args) {
        LinearAlgebra la = Blas.get();
        double[] c = new double[1];
        la.dgemm(false, false, 1, 1, 3, 1.0, new double[] {1, 2, 3}, 0, 1, new double[] {1, 1, 1}, 0, 3, 0.0, c, 0, 1);
        System.out.println("probe: " + la.name() + " " + c[0]);
    }
}
