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

"""The Module SDK folder's parts that need no Maven (``tools/launch/sdkbuild.py``): the kernel's library list read from the server jar, the
parent pom's managed rows (scope provided, one per library the pom does not manage already), and the typings and schemas copied in."""
from __future__ import annotations

import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
sys.path.insert(0, str(ROOT / "tools" / "launch"))
import sdkbuild  # noqa: E402

LIST = """# generated
com.fasterxml.jackson.core:jackson-databind:2.21.4 jackson-databind-2.21.4.jar com.fasterxml.jackson.databind
org.springframework:spring-context:6.2.19 spring-context-6.2.19.jar org.springframework.context

org.slf4j:slf4j-api:2.0.17 slf4j-api-2.0.17.jar org.slf4j
"""
POM = f"""<dependencyManagement><dependencies>
  <dependency><groupId>org.springframework</groupId><artifactId>spring-context</artifactId><version>1</version><scope>provided</scope></dependency>
  {sdkbuild.MANAGED_MARKER}
</dependencies></dependencyManagement>"""


def test_the_list_is_read_from_the_server_jar(tmp_path):
    jar = tmp_path / "drishti-server.jar"
    with zipfile.ZipFile(jar, "w") as z:
        z.writestr(sdkbuild.KERNEL_LIBRARIES, LIST)
    assert sdkbuild.kernel_libraries(jar) == LIST
    assert sdkbuild.kernel_libraries(None) is None
    assert sdkbuild.kernel_libraries(tmp_path / "absent.jar") is None
    other = tmp_path / "other.jar"
    zipfile.ZipFile(other, "w").close()
    assert sdkbuild.kernel_libraries(other) is None


def test_every_kernel_library_is_managed_as_provided_except_what_the_pom_manages_already():
    out = sdkbuild.manage(POM, LIST)
    assert "<artifactId>jackson-databind</artifactId><version>2.21.4</version><scope>provided</scope>" in out
    assert "<artifactId>slf4j-api</artifactId><version>2.0.17</version><scope>provided</scope>" in out
    assert out.count("<artifactId>spring-context</artifactId>") == 1                 # its own row stays the only one
    assert sdkbuild.manage(POM, None) == POM                                         # no list: the marker stays a comment


def test_a_line_that_is_not_coordinates_is_refused():
    import pytest
    with pytest.raises(sdkbuild.LaunchError):
        sdkbuild.manage(POM, "not-coordinates jar pkg\n")


def test_the_typings_and_schemas_of_the_repository_are_copied(tmp_path):
    written = sdkbuild.copy_typings_and_schemas(ROOT, tmp_path / "sdk")
    assert "schemas/contract.schema.json" in written
    assert any(w.startswith("typings/elements/drishti-core") for w in written)
    assert any(w.startswith("typings/drishti-render") for w in written)
    assert all((tmp_path / "sdk" / w).is_file() for w in written)
