# Project Drishti · Any data. Any domain. One grammar.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL.
#
# This file is the confidential and proprietary property of Ashutosh Sinha.
# Unauthorised copying, use, modification, distribution or disclosure of this
# file, via any medium, is strictly prohibited except with the express prior
# written permission of the copyright holder.
#
# See the LICENSE file in the root of this repository for the full terms.

"""varmath's near-tie rule is the engine's (VarMath.order, NearTieTest): P&Ls within 1e-12 of the vector's largest of each other order by
scenario index (the older first), so two sums that differ in their last bits pick the same VaR scenario; exact ties and clear orders as before.

    python3 -m pytest tools/packgen/var/test_varmath.py -q
"""
import math
import random
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import varmath as VM  # noqa: E402


def exact_order(v):
    return sorted(range(len(v)), key=lambda j: (v[j], j))


class NearTieTest(unittest.TestCase):

    def test_both_paths_pick_the_older_scenario(self):
        for seed in range(1, 21):
            r = random.Random(seed)
            java = [r.gauss(0, 1) * 1e7 for _ in range(509)]
            k = VM.rank_k(509, 0.99)
            o = exact_order(java)
            a, b = min(o[k - 1], o[k]), max(o[k - 1], o[k])
            x = java[o[k - 1]]
            java[a], java[b] = x * (1 + 3e-15), x
            blas = [math.nextafter(y, math.inf if r.random() < 0.5 else -math.inf) for y in java]
            blas[a], blas[b] = x, x * (1 + 3e-15)
            self.assertNotEqual(exact_order(java)[k - 1], exact_order(blas)[k - 1])
            self.assertEqual(VM.order(java)[k - 1], a)
            self.assertEqual(VM.order(blas)[k - 1], a)
            self.assertLessEqual(abs(VM.var(java) - VM.var(blas)), 1e-12 * abs(x))
            self.assertLessEqual(abs(VM.es(java) - VM.es(blas)), 1e-12 * abs(x))
            self.assertEqual(VM.tail(java), VM.tail(blas))

    def test_exact_ties_and_clear_orders_are_as_before(self):
        v = [5, -3, -3, 7, -10, 0.0, -0.0, 2]
        self.assertEqual(VM.order(v), [4, 1, 2, 5, 6, 7, 0, 3])
        r = random.Random(9)
        w = [r.gauss(0, 1) * 1e6 for _ in range(509)]
        self.assertEqual(VM.order(w), exact_order(w))
        self.assertEqual(VM.order([0.0] * 5), [0, 1, 2, 3, 4])


if __name__ == "__main__":
    unittest.main()
