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


def _installed(local, group, version, artifacts=("a-api", "a-core")):
    for a in artifacts:
        d = local.joinpath(*group.split("."), a, version)
        d.mkdir(parents=True)
        (d / f"{a}-{version}.jar").write_bytes(b"jar")
        (d / f"{a}-{version}.pom").write_text("<project/>")
        (d / "_remote.repositories").write_text("x")
        (d / f"{a}-{version}-sources.jar").write_bytes(b"s")
        (d.parent / "maven-metadata-local.xml").write_text("<m/>")


def _root(tmp_path):
    root = tmp_path / "root"
    root.mkdir()
    (root / "pom.xml").write_text("<properties><drishti.rachana.version>0.11.0</drishti.rachana.version>"
                                  "<drishti.lake.version>0.9.0</drishti.lake.version><drishti.tools.version>1.0.0</drishti.tools.version></properties>")
    return root


def test_the_three_pinned_products_are_carried_at_their_pinned_versions(tmp_path):
    root, local, repo = _root(tmp_path), tmp_path / "m2", tmp_path / "sdk" / "repo"
    for group, version in (("com.ash.rachana", "0.11.0"), ("com.ash.lake", "0.9.0"), ("com.ash.tools", "1.0.0")):
        _installed(local, group, version)
    _installed(local, "com.ash.rachana", "0.10.0")                                   # another version: not carried
    written = sdkbuild.copy_pinned_products(root, repo, local)
    assert "com/ash/rachana/a-core/0.11.0/a-core-0.11.0.jar" in written
    assert "com/ash/lake/a-api/0.9.0/a-api-0.9.0.pom" in written
    assert "com/ash/tools/a-core/1.0.0/a-core-1.0.0.jar" in written
    assert all((repo / w).is_file() for w in written)
    assert not any("0.10.0" in w or "_remote" in w or "metadata" in w or "sources" in w for w in written)


def test_a_product_missing_from_the_local_repository_is_refused_by_name(tmp_path):
    import pytest
    root, local = _root(tmp_path), tmp_path / "m2"
    _installed(local, "com.ash.rachana", "0.11.0")
    with pytest.raises(sdkbuild.LaunchError, match="com.ash.lake 0.9.0"):
        sdkbuild.copy_pinned_products(root, tmp_path / "repo", local)


def test_the_local_repository_is_named_by_the_variable_then_maven_args_then_the_default(tmp_path):
    assert sdkbuild.local_maven_repository({"DRISHTI_LOCAL_MAVEN_REPO": str(tmp_path)}) == tmp_path
    assert sdkbuild.local_maven_repository({"MAVEN_ARGS": f"-q -Dmaven.repo.local={tmp_path}/m2"}) == tmp_path / "m2"
    assert sdkbuild.local_maven_repository({}).name == "repository"
