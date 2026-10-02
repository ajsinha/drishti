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

import re, pathlib, sys
root = pathlib.Path(sys.argv[1])
m = {'1001':404,'1002':404,'1003':502,'1004':504,'1005':422,'1006':500,'2001':422,'2002':422,'2003':404,'2005':404,'2006':409,'2007':403,'2101':422,'2102':422,'3001':500,'4001':400,'4002':500,'4003':400,'4004':400,'5001':400,'5004':404,'5002':403,'5010':401,'6001':404,'6002':409,'6003':422,'6004':401,'6005':423,'6006':409,'6007':422,'6008':404,'6009':409,'5003':503}
n = 0
for f in list((root/'docs').rglob('*.md')) + list((root/'console/web/guides').glob('*.md')):
    for i, line in enumerate(f.read_text().splitlines(), 1):
        for s, code in re.findall(r'\b(4\d\d|5\d\d)`? `?DRS-(\d{4})', line):
            n += 1
            if m.get(code) != int(s):
                print(f.relative_to(root), i, s, 'DRS-'+code, 'expected', m.get(code))
print('checked', n)
