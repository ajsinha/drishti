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

"""The pack registry tool: keys, reproducible signed archives, the index, and verification catching tampering.

    python3 -m unittest tools/packreg/test_packreg.py        (needs openssl 3)
"""
import json
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import packreg  # noqa: E402


@unittest.skipUnless(shutil.which("openssl"), "openssl is needed")
class PackRegistryToolTest(unittest.TestCase):

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.pack = self.tmp / "widgets"
        (self.pack / "samples" / "widget").mkdir(parents=True)
        (self.pack / "pack.yaml").write_text("pack: widgets\nversion: 1.2.0\ntitle: Widgets\nextends:\n  - banking-core\n"
                                             "description: >-\n  Widgets and\n  their parts.\nkinds: [widget]\n")
        (self.pack / "samples" / "widget" / "W-1.json").write_text('{"widgetId": "W-1"}')
        with redirect_stdout(StringIO()):
            packreg.keygen(self.tmp / "keys" / "acme")
        self.key = self.tmp / "keys" / "acme.pem"
        self.registry = self.tmp / "registry"

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def test_publish_signs_a_reproducible_archive_and_verify_checks_it(self):
        with redirect_stdout(StringIO()):
            entry = packreg.publish(self.pack, self.registry, self.key, "acme")
        self.assertEqual(("widgets", "1.2.0", ["banking-core"], "Widgets and their parts."),
                         (entry["name"], entry["version"], entry["requires"], entry["description"]))
        first = (self.registry / "widgets-1.2.0.zip").read_bytes()
        self.assertEqual(first, packreg.archive(self.pack))                    # the same folder, the same bytes
        index = json.loads((self.registry / "index.json").read_text())
        self.assertEqual(1, len(index["packs"]))
        with redirect_stdout(StringIO()):
            packreg.publish(self.pack, self.registry, self.key, "acme")        # republishing replaces, never duplicates
            self.assertEqual(0, packreg.verify(self.registry, "acme", packreg.public_key(self.key)))
        self.assertEqual(1, len(json.loads((self.registry / "index.json").read_text())["packs"]))
        tampered = bytearray(first)
        tampered[len(tampered) // 2] ^= 1
        (self.registry / "widgets-1.2.0.zip").write_bytes(bytes(tampered))
        with redirect_stdout(StringIO()):
            self.assertEqual(1, packreg.verify(self.registry, "acme", packreg.public_key(self.key)))

    def test_a_key_is_never_overwritten(self):
        with self.assertRaises(SystemExit):
            packreg.keygen(self.tmp / "keys" / "acme")


if __name__ == "__main__":
    unittest.main()
