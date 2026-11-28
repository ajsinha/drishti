<!--
  Project Drishti · Any data. Any domain. One grammar.
  Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
  PROPRIETARY AND CONFIDENTIAL. See the LICENSE file in the root of this repository.
-->

# genomics: public gene and variant data, and your own VCF and gene files

For the person who keeps the genomics module's data fresh. Two jobs, both ending in the same lake (`genomicslake`) and the same four tables
(`variant`, `sample`, `gene`, `cohort`, the contracts of the genomics module):

1. **Public sources** run as **feeds**, like the feeds in [external/](../external/README.md): `clinvar` (NCBI ClinVar variants of a gene panel)
   and `ensembl` (Ensembl gene annotations of the same panel). One command each; small by default (a daily run is about ten requests and
   under 10 seconds).
2. **Your own files**: you drop VCF files (plain or gzipped) and gene, sample or cohort tables (CSV or JSON Lines) in one folder, and one
   command checks them against the contracts and loads them. No conversion by hand.

The engine is the shared one ([common/README.md](../common/README.md)): no second pull-and-load machinery. Depth on the module:
[modules/genomics/docs/guide.md](../../../modules/genomics/docs/guide.md); on feeds: [WORLD_DATA_GUIDE.md](../../../docs/guides/WORLD_DATA_GUIDE.md) section 19.

On Linux write `python3`; on Windows `py -3.13` and `\` in paths. `PY` below stands for that interpreter.

## What it loads

| Source | Command | Lake table | Rows (default scope) | Licence |
|---|---|---|---|---|
| `clinvar` | `feed run clinvar` | `genomics/variant` | about 570: pathogenic and likely pathogenic variants of BRCA1, BRCA2 and TP53, at most 200 a gene | public domain (NCBI) |
| `ensembl` | `feed run ensembl` | `genomics/gene` | 3 genes: chromosome, start, end, strand, biotype, protein length | open, attribution (EMBL-EBI) |
| your VCF | `genomics_run.py` | `genomics/variant`, `genomics/sample` | one variant row per position and alternate allele; one sample row per sample column | yours |
| your gene table | `genomics_run.py` | `genomics/gene` | one row per gene | yours |
| your sample table | `genomics_run.py` | `genomics/sample` | one row per sample (replaces the VCF's row of the same id) | yours |
| your cohort table | `genomics_run.py` | `genomics/cohort` | one row per cohort | yours |

Every row is in the contract of its kind (`modules/genomics/packs/genomics/contracts/<kind>.yaml`): the key is `VARIANT_ID`, `SAMPLE_ID`,
`GENE` or `COHORT_ID`; numbers are numbers, lists are lists. The public feeds add columns the contract does not list (`CLNSIG`,
`REVIEW_STATUS`, `CONDITIONS`, `CLINVAR_ID` on variants; `ENSEMBL_ID`, `BIOTYPE`, `DESCRIPTION` on genes); they are kept in the lake.

The feeds' pages (source, licence, schedule, options) are generated: [`config/feeds/clinvar.md`](../../../config/feeds/clinvar.md) and
[`config/feeds/ensembl.md`](../../../config/feeds/ensembl.md). The code: `clinvar.py`, `ensembl.py` (the feeds), `genomics_run.py` (your
files) and `genomics_kit.py` (the readers and the contract checks they share).

**Why not gnomAD or 1000 Genomes.** gnomAD is a GraphQL API and the 1000 Genomes slices are tabix-indexed VCF on FTP: neither fits the
shared GET-and-cache client without new machinery, and a "small" scope of either is still large. Download the slice you want yourself
(`bcftools view -r 17:43044295-43125483`) and drop the VCF in your folder: it loads like any other.

## Prerequisites (once)

1. **Python and the server jar**, as for every loader: `PY tools/drishti.py setup` checks the libraries and prints the `pip` command for
   what is missing (you run it; nothing is installed for you, no virtual environment). The load needs Java 21 and
   `lib/drishti-server-<version>-exec.jar` (`PY tools/drishti.py build jar`). `PY tools/drishti.py doctor` checks all of it.
2. **A contact in the User-Agent**, once, for the public feeds (NCBI and Ensembl ask who is calling): your team's name and a contact
   address of your own:

   ```text
   Linux     export DRISHTI_FEEDS_USER_AGENT="<your team name> <your team's contact address>"
   Windows   setx DRISHTI_FEEDS_USER_AGENT "<your team name> <your team's contact address>"   (then open a new window)
   ```
3. **A key** is optional: `NCBI_API_KEY` (an environment variable only, never in a file) raises NCBI's limit from 3 to 10 requests a
   second. The default run needs none.
4. **The lake**: `genomicslake` is a shipped lake file (`config/lakes/genomicslake.yaml`); its tables are known by the names above and live
   under the data folder (`data/`, or `DRISHTI_DATA_DIR`): `<data>/delta/genomicslake`. Nothing in the code names a path.
5. **For your own files**, the drop folder `<data>/genomics/inbox` (made on the first run; `GENOMICS_INBOX_DIR` or `--from-dir` names another).

## The daily routine, step by step

### A. The public feeds

1. **Look before pulling** (changes nothing, prints the requests):

   ```text
   Linux     PY tools/drishti.py feed run clinvar --dry-run
   Windows   py -3.13 tools\drishti.py feed run clinvar --dry-run
   ```
2. **Pull and load**:

   ```text
   Linux     PY tools/drishti.py feed run clinvar          then   PY tools/drishti.py feed run ensembl
   Windows   py -3.13 tools\drishti.py feed run clinvar    then   py -3.13 tools\drishti.py feed run ensembl
   ```

   Each prints what it fetched and one closing line, for example
   `feed ok exit=0 feed=clinvar date=2026-11-27 files=1 rows=568 load=java loaded=568 seconds=8.6`.
3. **Another panel or size** (any HGNC symbols; `--set` overrides the feed file for one run, edit `config/feeds/<feed>.yaml` to keep it):

   ```text
   Linux     PY tools/drishti.py feed run clinvar --set genes=MLH1,MSH2,APC --set max-per-gene=500
   Windows   py -3.13 tools\drishti.py feed run clinvar --set genes=MLH1,MSH2,APC --set max-per-gene=500
   ```

   `significance` (default `pathogenic, likely pathogenic`) takes ClinVar's clinical significance terms; an empty list takes every record.
4. **Date, history, again**: as in [external/README.md](../external/README.md): `--date 2026-11-26` for a business date, run the same
   command again and the date's rows are replaced whole, `--offline` uses the cache only, `--load inbox|python|none` picks another load
   route, `--lake-root DIR` writes a scratch copy of the lake instead of the real one.

Each run is a snapshot of the panel on its business date: the lake keeps one set of rows per date, so a variant ClinVar reclassifies
shows its new significance from the next date on, and the older dates keep what they said.

### B. Your own files

1. **Drop the files** in `<data>/genomics/inbox` (Windows `data\genomics\inbox`). The name decides what a file is:

   | Name | What it is | Columns |
   |---|---|---|
   | `anything.vcf`, `anything.vcf.gz` | variants and samples, VCF 4.x | the VCF's own; INFO `GENE`, `CSQ`, `IMPACT`, `AAPOS` fill gene, consequence, impact and protein position; `##SAMPLE=<ID=..,Cohort=..,Sex=..,Population=..>` describes the samples |
   | `genes*.csv`, `genes*.tsv`, `genes*.jsonl` (plain or `.gz`) | the gene kind | `GENE`, `CHROM`, `START`, `PROTEIN_LENGTH`, `DOMAINS` (JSON, or names separated by `;`), `VARIANTS` |
   | `samples*.csv` ... | the sample kind | `SAMPLE_ID`, `COHORT`, `SEX`, `POPULATION`, `VARIANTS` |
   | `cohorts*.csv` ... | the cohort kind | `COHORT_ID`, `SAMPLES`, `MEMBERS`, `VARIANTS`, `GENES` |

   Column names are matched without regard to case (`gene` is `GENE`); CSV may be comma, tab or semicolon separated; a column the contract
   does not list is kept. The **business date** is the date at the end of the name: `trio_2026-11-26.vcf.gz`, `genes.20261126.csv`;
   a file with none takes `--date`, else today. A table's name must start with `gene`, `sample` or `cohort`.
2. **Look first** (reads and checks; writes and moves nothing; shows the first rejected rows with their reasons):

   ```text
   Linux     PY tools/loaders/genomics/genomics_run.py --dry-run
   Windows   py -3.13 tools\loaders\genomics\genomics_run.py --dry-run
   ```
3. **Load**:

   ```text
   Linux     PY tools/loaders/genomics/genomics_run.py
   Windows   py -3.13 tools\loaders\genomics\genomics_run.py
   ```

   It prints one closing line, for example
   `genomics ok exit=0 files=2 variants=60 samples=12 genes=8 cohorts=0 rejected=0 refused=0 load=java loaded=80 seconds=9.2`.
   Named files instead of the folder: `genomics_run.py trio.vcf.gz genes.csv --date 2026-11-26`.

What the run does, in order: reads every file; checks every row against its contract (a row that fails is **rejected with its reason**, the
rest go on); writes the day's files under `<data>/genomics/loads/<date>/` (`<kind>.<date>.jsonl.gz`, `<kind>.<date>.rejects.jsonl`,
`manifest.json` with rows and SHA-256); loads them into `genomicslake` (`--load java`, the default, `python`, `inbox` or `none`): the tables
the module's screens read, and the only place they read; moves each file to `done/<date>/` in the drop folder, or to `rejected/` with a `<name>.why.txt`
when it could not be read at all.

### One day, several days, a day again

| Want | Do |
|---|---|
| a specific business date | put the date in the file names, or `--date 2026-11-26` |
| several dates at once | drop files with different dates in the names: each date loads on its own |
| the same date again | put its files in the drop folder again (copy them back from `done/<date>/`) and run: the date's rows of each kind are replaced whole |
| **add a file to a date** | drop **all** of that date's files again: a load replaces the date, it does not append to it. Or give the new file its own date |
| the server loads, not you | `--load inbox` drops one tar and its `.ready` in `<data>/dataload/inbox` (`--inbox DIR` names another); the server loads it at its next cycle |
| a scratch copy | `--lake-root DIR` (and `--out DIR`): nothing real is touched |

Several files of one date load together: two VCFs of a date are two sets of variants in one table; an id that appears in both keeps the
later file's row (the run says so). A sample table row replaces the VCF's row of the same sample.

## Scheduling

The feeds schedule like every feed (`feed schedule` prints one entry per enabled feed at its own time, `clinvar` 06:00 and `ensembl` 06:10):

```text
Linux cron      PY tools/drishti.py feed schedule --cron          then crontab -e and paste
Linux systemd   PY tools/drishti.py feed schedule --systemd       a .service and a .timer per feed
Windows         py -3.13 tools\drishti.py feed schedule --windows --root C:\drishti      then run the printed schtasks lines
```

Your own files are loaded when you run `genomics_run.py`; to load whatever was dropped each night, schedule that one command the same way
(Windows: `schtasks /Create /F /TN "Drishti genomics files" /SC DAILY /ST 20:00 /TR "py -3.13 C:\drishti\tools\loaders\genomics\genomics_run.py"`;
Linux: a crontab line `0 20 * * * python3 /opt/drishti/tools/loaders/genomics/genomics_run.py`). Its exit code is the result: 0 ok,
3 nothing was dropped (not an error), 2 rows rejected, 1 failed.

## How to check it worked

```text
Linux     PY tools/drishti.py feed status                  Windows   py -3.13 tools\drishti.py feed status
```

- **Feeds**: last run, status, rows, seconds, failures since the last success (`feed status --runs 5` adds the last five runs); a failed last
  run raises the alert `data-feed-failed` and clears at the next success.
- **The lake**: `PY tools/drishti.py lake status --lake genomicslake` lists the tables, their dates and rows.
- **Your files**: the closing line (`rejected=0 refused=0`), `<data>/genomics/loads/<date>/manifest.json` (each file's rows) and, when rows
  were rejected, `<kind>.<date>.rejects.jsonl` (one line each: the file, the row number, the reason, the row).
- **In the product**: open **Genomics** in the menu (Variants, Genes, Cohorts), or ask the API: `GET /api/v1/genomics/variants?gene=BRCA1`. Type
  `GENE TP53` in the command bar for the protein view. The screens read the lake tables this run wrote (`genomics.variant`, `.sample`, `.gene`,
  `.cohort`, through the genomics pack's connector), at most a minute after the load: there is no folder of the module's to fill, and no restart.

## Licences and rate limits

| Source | Terms | Redistribution | Rate limit | Attribution |
|---|---|---|---|---|
| NCBI ClinVar | NCBI data policy: public domain; submissions are the submitters' own interpretations | allowed | 3 requests a second without a key, 10 with `NCBI_API_KEY`; the feed pauses 0.4 s | "Contains data from NCBI ClinVar" |
| Ensembl | EMBL-EBI terms of use: open, free to use and redistribute | attribution | 15 requests a second; the feed pauses 0.2 s | "Contains data from Ensembl (EMBL-EBI)" |

The licence is copied into every manifest and ledger line of a feed run. Downloads are cached for 20 hours in
`<data>/datafeeds/<feed>/cache`, so a run that broke resumes without asking the source again. ClinVar is **not** clinical advice: it
is reference data for research and engineering, and its classifications change.

## When something goes wrong: exit codes and fixes

| You see | Means | Do this |
|---|---|---|
| feed exit 0 | ok | nothing |
| feed exit 1, `request(s) failed after retries` | NCBI or Ensembl did not answer (or HTTP 404: a gene symbol it does not know) | run again (the cache resumes it); check the symbol with `--dry-run`; behind a proxy, set `HTTPS_PROXY` |
| feed exit 1, `returned no variant ... for the panel` / `no gene for the panel` | nothing came back for the panel | check the gene symbols and, for ClinVar, `significance` |
| feed exit 2, `not gene symbols` | a name in `genes` has a space or a symbol character | use plain HGNC symbols: `BRCA1`, `HLA-A` |
| feed exit 2, `set the environment variable NCBI_API_KEY` | a key was asked for and not set | it is optional: remove the `keys:` entry or set the variable (never write it in a file) |
| feed exit 3 | another run of the feed holds `<data>/datafeeds/<feed>/run.lock` | wait and run again; a lock whose process is gone is taken over |
| feed exit 1, `load: ...` | the lake load failed | the line names the table and date; `PY tools/drishti.py doctor` checks Java 21 and the jar; `--load python` tries the other loader |
| `genomics_run.py` exit 3, `no files in ...` | the drop folder is empty | drop files in the folder it names (or name them on the command line) |
| `refused X: a table's name starts with gene, sample or cohort` | the name says nothing about the kind | rename: `genes_2026-11-26.csv`, `samples.jsonl` |
| `refused X: not a .vcf, .vcf.gz, .csv, .tsv or .jsonl file` | another file type | convert the table to CSV, or drop the VCF |
| `refused X: 'YYYYMMDD' is not a date` | the end of the name looks like a date but is not one | fix the date or remove it and use `--date` |
| `refused X: no #CHROM header line` / `a data line before the #CHROM header` | not a VCF, or a damaged one | check the file (`bcftools view -h`); a VCF needs its `#CHROM` line before the records |
| exit 2 with `rejected=N` | N rows failed their contract; the good rows loaded | read `<kind>.<date>.rejects.jsonl`: `START: 'abc' is not integer` (fix the cell), `GENE is empty (the key of gene)` (give the row its key) |
| `line N: not a VCF record` | a VCF line with fewer than 8 columns or a POS that is not a number | fix the line; the other records loaded |
| the screens do not show the new VCF | the module reads the lake tables and looks again a minute after it found one empty | check the run's closing line (`variants=N loaded=N`) and `PY tools/drishti.py lake status --lake genomicslake`; the file must have gone through `genomics_run.py`, the module opens no VCF itself |
| the run loaded, but part of a date is missing | a load replaces the whole date | drop all of the date's files together (see "a day again") |

## See also

[common/README.md](../common/README.md) (the shared engine, writing a feed) · [external/README.md](../external/README.md) (the same routine for
the reference-data feeds) · [mrloader/README.md](../mrloader/README.md) (a bank's files) · [the genomics module's guide](../../../modules/genomics/docs/guide.md)
· [DATA_GUIDE.md](../../../docs/guides/DATA_GUIDE.md) (the data inbox).

## Per-type guides

One page per feed and one for your own files, with the daily command, the schedule, late and replaced days, the checks and the reference: [docs/guides/loads/README.md](../../../docs/guides/loads/README.md) ([ClinVar](../../../docs/guides/loads/load-clinvar.md), [Ensembl](../../../docs/guides/loads/load-ensembl-genes.md), [your own VCF, gene and sample files](../../../docs/guides/loads/load-genomics-files.md)).
