# Region maps

One GeoJSON file per country (`<ISO3>.json`): the country's regions (states, provinces, counties) with ISO 3166-2 codes
(`AU-NSW`), written by `tools/maps/build_regions.py`. The console loads a file only when a map drills into that country.

| | |
|---|---|
| Source | Natural Earth 1:10m Admin 1 - States, Provinces (<https://www.naturalearthdata.com/downloads/10m-cultural-vectors/>), from <https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_10m_admin_1_states_provinces.geojson> |
| Licence | Public domain (Natural Earth terms of use: no permission needed, no attribution required; credited in THIRD-PARTY-NOTICES.md) |
| Processing | grouped by country (`adm0_a3`), features with the same ISO 3166-2 code merged, rings simplified with Douglas-Peucker (tolerance relative to the country's size, raised until the file is at most 1200 KB), coordinates rounded to 2 or 3 decimals, regions under 1.2% of the country's size written as Point features (`point: true`) so they stay clickable, longitudes of a country across the antimeridian (Russia, Fiji, the United States) moved past 180 so it draws as one block (`unwrapped: true`) |
| Files | 251 countries |
| Total size | 7.3 MB (limit 15 MB) |
| Largest | CAN 187 KB, GBR 173 KB, RUS 145 KB, ITA 136 KB, IND 124 KB, CHN 124 KB, THA 109 KB, SVN 104 KB (limit 1.5 MB each) |

`../world-points.json` lists the 77 countries and territories the 1:110m world map has no shape for (Cayman Islands,
Bermuda ...) with one point each, so a map can draw them as clickable dots.

Natural Earth's placeholder codes for regions with no ISO 3166-2 code (`AU-X02~`) are kept without the tilde (`AU-X02`).
Regenerate with `python3 tools/maps/build_regions.py` (about a minute; downloads the 40 MB source once into `~/.cache/drishti-maps`).
