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

# Headless CLI on hostile inputs. Output goes to $W/cli; every file created outside --out is reported.
R=/home/ashutosh/IdeaProjects/drishti
W=$(cat "$(dirname "$0")/.workdir"); C=$W/cli
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export DRISHTI_PACKS_DIR=$R/packs DRISHTI_PACKS=market-risk
JAR=$R/drishti-server/target/drishti-server-1.13.0-exec.jar
run() { # label, args...
  local label=$1; shift
  touch $C/.marker; sleep 1
  ( cd $C && timeout 180 $JAVA_HOME/bin/java -Xmx512m -jar $JAR sutra "$@" > $C/stdout.txt 2> $C/stderr.txt; echo "exit=$?" > $C/exit.txt )
  new=$(find $W /tmp -maxdepth 3 -newer $C/.marker -type f \( -path "$W/*" -o -name "*.html" -o -name "*.xml" \) -not -path "$C/stdout.txt" -not -path "$C/stderr.txt" -not -path "$C/exit.txt" -not -path "$W/server.log" -not -path "$W/console.log" -not -path "$W/data/*" -not -path "$W/identity*" -not -path "*/.marker" 2>/dev/null | grep -v "claude-1000/.*/tasks/\|sutra-cli" | head -8 | tr '\n' ' ')
  echo "### $label | $(cat $C/exit.txt) | stdout: $(head -c 300 $C/stdout.txt | tr '\n' '~') | stderr: $(grep -v '^\s*at \|^$' $C/stderr.txt | head -3 | cut -c1-200 | tr '\n' '~') | new files: $new"
  grep -c "^\s*at \|Exception in thread" $C/stderr.txt | sed 's/^/    stacktrace lines: /'
}
rm -rf $C; mkdir -p $C/in/sutras $C/in/tests/aa $C/out $C/outside
cat > $C/in/sutras/aa.v1.sutra.yaml <<'Y'
rachana: 1
sutra: aa
version: 1
match: { kind: probe, priority: 1 }
title: { id: $.id, pill: "<img src=x onerror=alert(1)>" }
strip:
  - { label: "<b>L</b>", bind: $.name }
panels:
  - { id: p, kind: kv, title: "<script>alert(2)</script>", columns: [ { label: Name, bind: $.name } ] }
Y
echo '{"id":"A1","name":"</td><script>alert(3)</script>\"&"}' > $C/in/tests/aa/s.json
run "baseline preview --out" preview $C/in --out $C/out
ls $C/out | head
echo "html escapes: script tags raw in snapshot? $(grep -c '<script>alert' $C/out/*.html) ; onerror raw? $(grep -c 'onerror=alert' $C/out/*.html)"
run "out = path with .. " preview $C/in --out $C/out/../out2
run "out is existing file" preview $C/in --out $C/in/sutras/aa.v1.sutra.yaml
ln -sfn $C/outside $C/out/linkdir
run "out is symlink dir to outside (user supplied)" preview $C/in --out $C/out/linkdir
ls $C/outside | head -3
# sutra file name with traversal-looking stem
cp $C/in/sutras/aa.v1.sutra.yaml "$C/in/sutras/..x.sutra.yaml"; cp $C/in/sutras/aa.v1.sutra.yaml "$C/in/sutras/a b;c\$d.sutra.yaml"
run "weird stems" preview $C/in --out $C/out
run "missing path" preview $C/nonexistent
run "no args" lint
run "unknown flag" preview $C/in --bogus x
run "--out missing value" preview $C/in --out
run "--junit /proc (unwritable)" lint $C/in --junit /proc/x.xml
# JUnit XML injection through sutra name / messages
cp $C/in/sutras/aa.v1.sutra.yaml "$C/in/sutras/q\"><x&amp;.sutra.yaml"
run "junit xml special chars in file names" test $C/in --junit $C/junit.xml
python3 -c "import xml.dom.minidom as m; m.parse('$C/junit.xml'); print('    junit well-formed')" 2>&1 | tail -1
# huge file: 120 MB JSON
python3 - <<P
open("$C/in/tests/aa/huge.json","w").write('{"id":"H","name":"'+'A'*(120*1024*1024)+'"}')
open("$C/deep.json","w").write('['*100000+']'*100000)
open("$C/big_array.json","w").write('['+','.join(['{"a":1}']*3000000)+']')
P
run "huge 120MB sample (preview)" preview $C/in --samples $C/in/tests/aa/huge.json --out $C/out
run "deep JSON 100000 (shape)" shape $C/deep.json
run "3M-element array (shape)" shape $C/big_array.json
rm -f $C/in/tests/aa/huge.json $C/big_array.json
# binary/garbage Sutra
head -c 100000 /dev/urandom > "$C/in/sutras/junk.v1.sutra.yaml"
run "binary sutra file" lint $C/in
rm -f "$C/in/sutras/junk.v1.sutra.yaml"
# symlinked input: tests dir pointing outside, Sutra symlink to /etc/passwd
ln -sfn /etc/passwd "$C/in/sutras/pw.v1.sutra.yaml"; ln -sfn /etc "$C/in/tests/etcdir"
run "symlinked sutra -> /etc/passwd" lint $C/in
run "shape of /etc/passwd" shape /etc/passwd
