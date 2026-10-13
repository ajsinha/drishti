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

"""The scratch stack for the distribution tests and pictures: tests/bi_report_stack.py (security on, the sample report "Trades overview" over a small file lake) with the
sample dataset given row rules and a masked column, and the server pointed at a local fake SMTP (tests/fake_smtp.py), so each person sees different figures and no
mail leaves the machine. Ports come from the environment (DRISHTI_BI_REPORT_SERVER_PORT, DRISHTI_BI_REPORT_CONSOLE_PORT; the work in this repository uses 18950 and 17950)."""
from __future__ import annotations

import bi_report_stack
from bi_report_stack import CONSOLE_URL

REPORT = "file:report:trades-overview"
SECURITY = """
security:
  rows:
    - { role: desk-head, where: "desk in user.desks" }
    - { role: [risk, analyst, bi-writer, admin], where: "True" }
  masked: [mtm]
"""


class DistStack(bi_report_stack.ReportStack):
    """rita sees every desk and MTM, hana only DESK-FX, ann every desk but MTM is masked, wes writes (and may subscribe other people)."""

    def __init__(self, tmp, smtp_port: int):
        super().__init__(tmp, delta_root="")
        self.server_args = ["--spring.mail.host=127.0.0.1", f"--spring.mail.port={smtp_port}", f"--drishti.collab.console-url={CONSOLE_URL}",
                            "--drishti.collab.email.from=drishti@desk.test", "--drishti.distribution.subscriptions.tick=1h"]

    def prepare(self, work):
        ds = work / "config" / "bi" / "datasets" / "trades-basic-trade.dataset.yaml"           # the rules live on the dataset the model trades-basic reads
        ds.write_text(ds.read_text() + SECURITY)
