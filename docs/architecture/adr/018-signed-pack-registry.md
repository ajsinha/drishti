<!--
  Project Drishti · Any data. Any domain. One grammar.

  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
  All rights reserved.

  PROPRIETARY AND CONFIDENTIAL.

  This file is the confidential and proprietary property of Ashutosh Sinha.
  Unauthorised copying, use, modification, distribution or disclosure of this
  file, via any medium, is strictly prohibited except with the express prior
  written permission of the copyright holder.

  See the LICENSE file in the root of this repository for the full terms.
-->
# ADR-018: Packs come from a signed, versioned registry

| Status | Date | Decider |
|---|---|---|
| Accepted (built in 1.12) | 2026-10-01 | Ashutosh Sinha |

## Context
Packs were folders in the server's `packs/` directory: copied by hand, with no version history on the server, no
way to know who made a pack, and no rollback. A pack carries Sutras, vocabulary, connectors and roles, so a pack
from the wrong hands can point a connector at the wrong data or widen a role.

## Decision
1. **A registry is plain files**: `index.json` and one zip per pack version, served over HTTPS or read as a folder.
   No registry server to run; any web server or shared drive will do.
2. **Every archive is signed with Ed25519** by its publisher (`tools/packreg`, standard library and `openssl`). A
   server lists the publishers it trusts (`drishti.packs.registry.trusted-keys`: name to public key). Anything not
   signed by a trusted key is refused.
3. **Installing checks before it changes anything**: size and SHA-256 against the index, the signature, that every
   file stays inside the pack and the unpacked size is bounded, and that `pack.yaml` names the same pack and version.
   It then goes to its own folder (`drishti.packs.installed-dir`), looked in before the shipped packs, and the
   server's usual pack check and in-place restart follow (with the files put back if the server cannot start).
4. **Versions are kept**: the version an install replaces is kept, and **Roll back** puts it back.
5. **Archives are reproducible**: the same pack folder makes the same archive (fixed order and times), so a
   reviewer can rebuild and compare.

## Consequences
- Publishers must keep their private keys safe; a stolen key lets someone publish as them until servers drop it
  from `trusted-keys`. Rotate by adding the new key, republishing, then removing the old one.
- Plain `http:` registries are refused (signatures would still hold, but the index could be replayed); a test-only
  switch allows it.
- Installs are audited (`pack-installed`, `pack-upgraded`, `pack-rolled-back`) with the publisher and SHA-256.

## Alternatives considered
- **Packages in Maven or PyPI**: heavy for content that is mostly YAML, and their trust model is the repository's,
  not the site's.
- **Git submodules or a pack repository**: good for authors, but servers would need git and credentials, and a
  checkout is not a signed release.
- **Checksums without signatures**: protects against corruption, not against a changed index.
