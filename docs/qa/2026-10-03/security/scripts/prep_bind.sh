#!/bin/bash
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

# prepares files for the file-binding tests in the scratch work dir
W=$(cat "$(dirname "$0")/.workdir")
mkdir -p $W/sutras/sub $W/outside
printf 'rachana: 1\nsutra: other-live\nversion: 1\ndescription: the other Sutra\nmatch: { kind: otherkind, priority: 1 }\ntitle: { id: $.id }\npanels: []\n' > $W/sutras/existing.yaml
printf 'top_secret: OUTSIDE-FILE-MARKER-31337\n' > $W/outside/secret.yaml
printf 'top_secret: OUTSIDE-DIR-MARKER\n' > $W/outside/x.yaml
printf 'top_secret: SIBLING-MARKER\n' > $W/secret_sibling.yaml
ln -sfn $W/outside/secret.yaml $W/sutras/link.yaml
ln -sfn $W/outside $W/sutras/linkdir
ln -sfn $W/sutras/existing.yaml $W/sutras/sub/inner.yaml
ls -la $W/sutras
