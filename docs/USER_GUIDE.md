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
# User guide

## Opening things

Open the terminal at `/t`, or use **Open the terminal** on the landing page. Type a command:

```
TRD IRS-48213 <GO>        a trade
NSET NS-NORTH-01 <GO>     a netting set
CRV USD-SOFR <GO>         a curve
IRS-48213                 a bare identifier works when its pattern names the kind
```

Mnemonics come from the enabled domain packs. With the finance pack: `TRD` trade · `NSET` netting set · `CSA` credit support annex · `AGR` master agreement ·
`CRV` curve · `CPTY` counterparty · `LIM` credit limit · `CLR` clearing account · `SPEC` contract
spec · `IDX` rate index · `FXS` FX spot · `BOOK` book · `FIX` rate fixings. With the logistics pack: `SHP` shipment · `CTR` container
· `VSL` vessel · `PORT` port. Sites add their own in `drishti.commands.mnemonics`; see PACKS.md.

The generated packs bring these (the landing page and each pack's guide list example commands):

| Pack | Mnemonics |
|---|---|
| `banking-core` | `CPTY` counterparty · `GRP` counterparty group · `ISS` issuer · `AGR` master agreement · `CCP` central counterparty · `LE` bank legal entity · `BOOK` book · `DESK` desk · `TRDR` trader · `CAL` holiday calendar · `CSA` credit support annex · `CLR` clearing account |
| `market-data` | `CRV` interest rate curve · `REPO` repo curve · `FX` fx spot rate · `FXF` fx forward curve · `FXV` fx volatility surface · `IRV` swaption volatility cube · `CPV` cap/floor volatility surface · `EQ` equity · `EQX` equity index · `DIV` dividend curve · `EQV` equity volatility surface · `CDS` credit curve · `INF` inflation index · `INFC` inflation curve · `CMD` commodity · `CMDC` commodity forward curve · `CMDV` commodity volatility surface · `FIX` rate index fixings · `BND` bond (security master) · `CORR` correlation matrix |
| `trading` | `TRD` trade |
| `market-risk` | `VAR` var / expected shortfall · `SCN` stress scenario · `STR` stress result · `FRTB` frtb sensitivities · `PNL` p&l explain |
| `counterparty-risk` | `NSET` netting set · `LIM` credit limit · `EXP` exposure profile · `CVA` cva / xva · `SACCR` sa-ccr exposure · `COLL` collateral balance · `MC` margin call · `SIMM` isda simm initial margin |
| `liquidity-risk` | `LCR` liquidity coverage ratio · `NSFR` net stable funding ratio · `MLAD` maturity ladder · `HQLA` hqla holding · `FUND` funding source · `LST` liquidity stress result · `IDL` intraday liquidity |
| `climate-risk` | `CLIM` climate profile · `FE` financed emissions · `NGFS` climate scenario · `CST` climate stress result · `PHY` physical-risk asset · `GAR` green asset ratio |
| `operational-risk` | `LOSS` operational loss event · `RCSA` risk and control assessment · `KRI` key risk indicator · `ISSUE` issue and action · `OPSCN` operational-risk scenario · `VEND` third-party (vendor) · `CYBER` cyber incident · `OPCAP` operational-risk capital |
| `retail-banking` | `CUST` customer · `ACCT` deposit account · `MTG` mortgage · `CARD` card account · `PLN` personal loan · `BRN` branch · `COLC` collections case · `RPF` retail portfolio (ifrs 9) |
| `genomics` | `GENE` gene · `VRNT` variant · `PROT` protein · `PWY` pathway · `SMPL` sample · `SEQ` sequencing run · `EXPR` expression study · `TRIAL` clinical trial |
| `politics-society` | `JUR` jurisdiction · `PARTY` party · `CAND` candidate · `ELEC` election · `POLL` opinion poll · `BILL` bill · `REGN` region · `SOCI` social indicator |
| `economics` | `ECON` economy · `MACRO` macro indicator · `CBD` central-bank decision · `FCST` economic forecast · `TFLOW` trade flow · `LABR` labour market · `FISC` fiscal position · `CPIB` consumer-price basket |


### Search by value

Add `where` to a mnemonic to find entities by what they contain:

```
TRD where notional >= 250m and assetClass = 'Rates' order by mtm desc limit 20
TRD where counterparty.name contains 'Meridian'
CUST where totalDeposits > 100k order by totalDeposits desc
VRNT where significance contains 'pathogenic'
```

Fields are named as in the document, with dots and brackets for nested ones (`counterparty.name`,
`legs[0].rate`). Compare with `= != < <= > >=`, combine with `and`, `or` and `not`, match text with `contains`
and `startswith` (case-insensitive; `contains` also looks inside lists), and write amounts as `250k`, `1.5m`
or `2bn`. `order by <field> [desc]` sorts and `limit N` caps the list (100 by default). The results show the
fields the query uses, labelled as in views. A search follows the date in the top bar, sees only what your role
may see (a masked field never matches), and says so when a slow source or the scan limit may have left
entities out. **Watch as a monitor** saves the first 50 results as a monitor, so the list updates live.

### Suggestions as you type

The command line suggests in a dropdown, as the Bloomberg terminal does:
- **Empty:** your recently opened entities.
- **A partial word:** matching mnemonics (`T` → `TRD`), your recents, and matching entities of any kind.
- **A mnemonic, then text:** entities of that kind (`TRD IRS-4` → `IRS-47102`, `IRS-48213`, …).

Matched characters are highlighted.

## Keyboard

| Key | Does |
|---|---|
| `F1` | help for the screen you are on |
| `/` | focus the command line from anywhere |
| `↑` `↓` | move through suggestions |
| `Tab` | complete the highlighted suggestion |
| `Enter` | open it (`<GO>`) |
| `Esc` | close the suggestions or the raw drawer |
| `F2`–`F6` | jump to a panel (shown in each panel's header and the footer) |
| `F7` | open the main linked entity (netting set, clearing account, counterparty) |
| `F8` | impact: what depends on this entity, what that rolls into, and the amount at stake |
| `F9` | raw JSON of the entity, with its source and generation |
| `Alt+←` | back |

## Reading a view

- **Title line:** what the entity is (the pill), its identifier, and its counterparty. The counterparty is a link.
- **Strip:** the key figures. The highlighted one is what the desk watches most (MTM, last price, PFE).
  Negative values use a true minus sign and the negative colour; positive values carry a `+`.
- **Panels:** main detail on the left; context on the right (curve, linked entities, sensitivities).
  An *inferred* tag means inference laid the panel out; hover over it to see why.
- **Linked entities:** every identifier is a door. The badge beside a link (`EE 4.1m`,
  `threshold 0`, `live`) is read from the target. *pending* means it didn't arrive in time.
- **Breadcrumbs:** `← IRS-48213 / NS-NORTH-01` shows the path you followed in this browser tab.
- **How this view was built:** the Sutra and version (or *inference only*), the data fingerprint,
  and the source with its generation.
- **No data available:** a panel whose data the document does not have (or has in an unexpected shape) says so.
  The rest of the view is unaffected. Real feeds are often incomplete.

## Business date: live or a day in the past

The date box in the top bar says which day you are looking at.

- **Live** (green, the default) is the current business date on the New York calendar. Weekends and
  holidays count as the business day before. Views stream: numbers tick and flash.
- **Pick a date** in the calendar to see that day's end-of-day data: the trade, its curve, its netting set, the
  impact and the suggestions, all as of that date. A picked date is a **static snapshot**, even if you pick today.
  The box turns amber, and nothing streams.
- A weekend or holiday rolls back to the business day before; the box shows `↩` with the date you asked for.
- Press **Live** to go back.
- If a source keeps no history, the view says so ("*… is not a dated source: this shows its current data*").
  If the latest data is from an earlier day than the one you picked, it says which day.
- The footer shows the date the data is for (`as of 2026-09-25`).
- **Known at.** With a date picked, the clock button beside it asks *as known when?* Pick a time (New York time)
  to see the date as it was known then; later corrections are left out. The × goes back to the latest
  knowledge. Sources that keep versions (Delta Lake) honour it; others show their only version.

### Compare: what changed

**Compare** (beside *Alert* in a view of a dated entity) lists every field that differs between two business
dates: the field's label and path, both values, and the change for numbers. By default it compares the date
in the top bar with the business day before. Pick any two dates, or give the earlier side a *known at* time to
see what was restated for the same date. Lists are matched by their identifiers or natural keys (a tenor, a
date, a code), so an inserted cashflow shows as one addition, not as every later cashflow changed. Filter to
*changed*, *added* or *removed*.

## Your settings

*My account → Settings* keeps your preferences with your account, so they follow you to any browser:

- **Theme**: the one you pick from the theme menu is saved here too.
- **After signing in, open**: the terminal (`/t`), a workspace (`/w/Rates`), a monitor (`/m/Watchlist`) or a
  view (`/v/trade/T-10001`).
- **Clock time zone**: the top bar's clock.
- **Density**: *compact* fits more on the screen.
- **Flash changed values**: turn off the flash on live changes.
- **Search results**: how many a search shows unless it says `limit`.
- **Pin**: the pin in a view's header adds the entity to *Pinned* on the terminal home (up to 20); press it again
  to unpin.

## Export and share

- **CSV.** The ↓ icon in a panel's header downloads that panel: tables and ladders as they are, key/value panels as
  field and value, charts as a column per series, surfaces as a grid, bars as label and value. Numbers shown as
  `−412,580` or `4.25%` arrive as `-412580` and `0.0425`, so a spreadsheet can add them up. Search results and
  comparisons have a **CSV** link too. File names carry the business date when one is picked.
- **JSON.** **JSON** in the view's header downloads the document, as your role may see it.
- **Print.** **Print** prints the view, or saves it as PDF from the print dialog: a light page without the
  top bar, keys or buttons, whatever your theme.
- **Share.** **Share** copies a link to the view. Live links open live; with a date picked, the link opens that
  date, and with a known-at time, that exact moment, so a colleague sees what you see (within their own
  permissions).

## Monitors and alerts

**Monitors** (`/m`) are live watchlists: each row is an entity with its key figures ticking. **Alerts**
(`/alerts`, or **Alert** in a view's title line) are rules such as `$.utilisation > 0.8`. The server
checks them on every change and alerts once, with a toast and a count on the bell. See *Monitors and
alerts* in help.

## Workspaces

**Workspaces** in the top bar (`/w`) puts several live views on one screen. A pane can follow
another: pick a trade in the netting-set pane and the next pane opens it. Start from *Credit desk*,
*Rates* or *Cross-asset*, change it, and **Save** it to your account. See the Workspaces guide in help.

## Help

**Help** in the top bar (or `F1`) opens the help centre: three tutorials, guides and every reference,
with search. The **?** in each panel header explains that panel kind. **About** shows the version, the
loaded Sutras and the health of each source.

## On a phone

Drishti works in Safari on iPhone and Chrome on Android, with no app to install. To put it on your home
screen, use *Share → Add to Home Screen* (iPhone) or *⋮ → Add to Home screen* (Android). It then opens
full-screen at the terminal.

- **Function keys** become a swipeable row of buttons at the bottom of every view. Tap **F9 Raw JSON**,
  **F7 Netting set**, **← Back**, and so on.
- **Command line:** type a command, and tap a suggestion to open it.
- **Layout:** panels stack in one column, the strip shows two figures per row, and wide tables scroll
  sideways inside their panel.
- **Live values** keep ticking. The top bar still shows the p99.
- **Studio** (authoring) is left out on phones. Everything else, including help and administration, works.

## Domain packs

If your administrator has given you more than one pack (say *Finance* and *Logistics*), the box icon in
the top bar lets you choose which to see. Commands, suggestions, examples and help follow your choice.

## Themes

The palette menu offers seven themes:
- **Terminal** (default);
- **Parchment** (light);
- **Wall Street** (the Bloomberg Terminal colour scheme: black, amber, yellow);
- **Blue** and **Green**;
- **Crimson** and **Crimson dark** (the Maya palette: Harvard crimson with indigo).

The choice is remembered per browser.

Every theme has two gradient colours of its own (terminal: amber to blue; crimson: crimson to navy; blue: cyan to
violet, and so on). They shade the page, the top bar, the view header and panel headers, accent buttons and titles;
panel bodies, tables and figures stay on solid colours, so they read as clearly as before. If your system asks for
more contrast or less transparency, the solid look is used.
