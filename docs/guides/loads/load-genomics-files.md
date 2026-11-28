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
# Load your own genomics files every day

Your VCF files (plain or gzipped) and gene, sample or cohort tables (CSV, TSV or JSON Lines), dropped in one folder and loaded by one command: it checks every row against the genomics contracts, loads `genomics/variant`, `genomics/sample`, `genomics/gene` and `genomics/cohort`
into the lake **`genomicslake`**, the tables the genomics module's screens read (the module opens no VCF file itself: the files go through the loader into the lake). The public sources (ClinVar, Ensembl) are separate: [ClinVar](load-clinvar.md) and [Ensembl](load-ensembl-genes.md).

Run every command from the Drishti folder (the one that holds `tools`): `/opt/drishti` on Linux and `C:\drishti` on Windows in the examples. Nothing is installed for you and there is no virtual environment: use the Python you run Drishti with. Loading needs Java 21 and the server jar in `lib` (`drishti.py build jar`); converting needs neither. A date in an example is an example: use the business date of your file.

## Every day

1. **Drop the files** in `<data>/genomics/inbox` (`data/genomics/inbox`, Windows `data\genomics\inbox`; made on the first run). The name decides what a file is:

| Name | What it is |
|---|---|
| `anything.vcf`, `anything.vcf.gz` | variants and samples, VCF 4.x (INFO `GENE`, `CSQ`, `IMPACT`, `AAPOS` fill gene, consequence, impact and protein position; `##SAMPLE=<ID=..,Cohort=..,Sex=..,Population=..>` describes the samples) |
| `genes*.csv`, `.tsv`, `.jsonl` (plain or `.gz`) | the gene kind: `GENE, CHROM, START, PROTEIN_LENGTH, DOMAINS, VARIANTS` |
| `samples*.csv` ... | the sample kind: `SAMPLE_ID, COHORT, SEX, POPULATION, VARIANTS` |
| `cohorts*.csv` ... | the cohort kind: `COHORT_ID, SAMPLES, MEMBERS, VARIANTS, GENES` |

The business date is the date at the end of the name (`trio_2026-11-26.vcf.gz`, `genes.20261126.csv`); a file with none takes `--date`, else today.

2. **Look first** (reads and checks; writes and moves nothing; shows the first rejected rows with their reasons), then **load**:

```text
Linux     cd /opt/drishti
          python3.13 tools/loaders/genomics/genomics_run.py --dry-run
          python3.13 tools/loaders/genomics/genomics_run.py
Windows   cd C:\drishti
          py -3.13 tools\loaders\genomics\genomics_run.py --dry-run
          py -3.13 tools\loaders\genomics\genomics_run.py
```

Named files instead of the folder: `genomics_run.py trio.vcf.gz genes.csv --date 2026-11-26`. **What you see** (a real run of this page's author on the test fixtures of `tools/loaders/common/tests/fixtures/genomics`, into a scratch data folder; three of the six files are public-source JSON that does not belong in the drop folder and are refused, and two rows are rejected on purpose):

```text
  read trio_2026-11-26.vcf: vcf, business date 2026-11-26
  refused clinvar_search_BRCA1.json: ...: a table's name starts with gene, sample or cohort (genes.csv, samples_2026-11-26.jsonl); a VCF ends in .vcf or .vcf.gz
  read genes_2026-11-26.csv: gene, business date 2026-11-26
  read samples_2026-11-26.jsonl: sample, business date 2026-11-26
  note: samples_2026-11-26.jsonl: sample KID appears again: the later one is kept
  dry run 2026-11-26: 4 variant, 3 sample, 2 gene; 2 rejected
    reject samples_2026-11-26.jsonl row 2: SAMPLE_ID is empty (the key of sample)
    reject genes_2026-11-26.csv row 3: START: 'notanumber' is not integer (invalid literal for int() with base 10: 'notanumber')
2026-11-27T19:43:48-05:00 genomics dry-run exit=2 files=6 variants=4 samples=3 genes=2 cohorts=0 rejected=2 refused=3 load=none loaded=0 seconds=0.0
```

and the load:

```text
  loaded gene 2026-11-26: 2 rows into lake genomicslake (java)
  loaded sample 2026-11-26: 3 rows into lake genomicslake (java)
  loaded variant 2026-11-26: 4 rows into lake genomicslake (java)
2026-11-27T19:43:59-05:00 genomics rejects exit=2 files=6 variants=4 samples=3 genes=2 cohorts=0 rejected=2 refused=3 load=java loaded=9 seconds=11.7
```

On a clean day the closing line reads `genomics ok exit=0 files=2 variants=60 samples=12 genes=8 cohorts=0 rejected=0 refused=0 load=java loaded=80 ...` (the shape from the loader README). The run reads every file, checks every row (a failing row is **rejected with its reason**, the rest go on), writes the day's files under `<data>/genomics/loads/<date>/` (`<kind>.<date>.jsonl.gz`, `<kind>.<date>.rejects.jsonl`, `manifest.json`), loads them into the lake (the tables the module's screens read: they open no VCF file) and moves each file to `done/<date>/` in the drop folder, or to `rejected/` with a `<name>.why.txt` when it could not be read at all.

## Schedule it

Your own files are loaded when you run the command, so schedule it for **after your files land** (exit **3** means nothing was dropped, which is not an error):

**Linux cron** (`crontab -e`; mind that `%` must be written `\%` in a crontab):

```text
SHELL=/bin/bash
0 20 * * * cd /opt/drishti && python3.13 tools/loaders/genomics/genomics_run.py >> $HOME/drishti-genomics.log 2>&1
```

Cron starts with an empty environment: put the working folder (`cd /opt/drishti`) and any variable in the line or the crontab, as above. 

**Windows Task Scheduler.** Save the job as a script, then register it:

```powershell
# C:\drishti\jobs\load-genomics-files.ps1
Set-Location C:\drishti
py -3.13 tools\loaders\genomics\genomics_run.py *>> C:\drishti-logs\genomics.log
exit $LASTEXITCODE
```

```text
mkdir C:\drishti-logs
schtasks /Create /F /TN "Drishti load genomics files" /SC DAILY /ST 20:00 /TR "powershell -NoProfile -ExecutionPolicy Bypass -File C:\drishti\jobs\load-genomics-files.ps1"
schtasks /Run /TN "Drishti load genomics files"
schtasks /Query /TN "Drishti load genomics files" /V /FO LIST
```

The task's *Last Run Result* is the script's exit code. By hand: Task Scheduler → Create Task → *Triggers* New (weekly, Monday to Friday, at the time your extract lands plus a margin) → *Actions* New → Start a program `powershell`, arguments `-NoProfile -ExecutionPolicy Bypass -File C:\drishti\jobs\load-genomics-files.ps1`, **Start in** `C:\drishti` → *Run whether user is logged on or not*.

## When a day fails or is late

- **A late file:** drop it with its date in the name and run the command; each date loads on its own. Several dates at once: drop files with different dates in their names.
- **The same date again:** put its files back in the drop folder (copy them from `done/<date>/`) and run. **A load replaces the date**, it does not append: to add a file to a date, drop **all** of that date's files again, or give the new file its own date.
- **Several files of one date** load together: two VCFs of a date are two sets of variants in one table; an id in both keeps the later file's row (the run says so); a sample table row replaces the VCF's row of the same sample.
- **Rejected rows** (exit 2): read `<data>/genomics/loads/<date>/<kind>.<date>.rejects.jsonl` (the file, the row number, the reason, the row), fix the source rows, drop the file again.
- **Take a date out:** `python3.13 tools/drishti.py lake delete --lake genomicslake --table genomics/variant --date 2026-11-24 --dry-run`, then without `--dry-run`.
- **The server loads, not you:** `--load inbox` drops one tar and its `.ready` in `<data>/dataload/inbox`. **A scratch copy:** `--lake-root DIR --out DIR`: nothing real is touched.
- Exit codes: **0** ok, **1** failed, **2** rows rejected, **3** nothing was dropped.

## Check it loaded

```text
Linux     python3.13 tools/drishti.py lake status --lake genomicslake
Windows   py -3.13 tools\drishti.py lake status --lake genomicslake
```

```text
lake <scratch>/data/delta/genomicslake
tables (3):
  table             rows  files  size    partitions  dates  latest date  last build
  genomics/gene     5     2      5.6 KB  2           2      2026-11-27   2026-11-28 00:43:52Z
  genomics/sample   3     1      2.7 KB  1           1      2026-11-26   2026-11-28 00:43:56Z
  genomics/variant  4     1      4.6 KB  1           1      2026-11-26   2026-11-28 00:43:59Z
lock: free
```

(Real output of the run above; the gene table also holds the Ensembl run's 3 rows of 2026-11-27.) Also: the closing line (`rejected=0 refused=0`), `<data>/genomics/loads/<date>/manifest.json` (each file's rows and SHA-256) and, in the product, **Genomics** in the menu (Variants, Genes, Cohorts) within a minute of the load (no restart, no folder to fill), or `GET /api/v1/genomics/variants?gene=BRCA1`. The screens read the lake tables this run wrote; the files you drop reach them only through this loader.

## Reference

**What it writes.** Lake `genomicslake`, tables `genomics/variant` (one row per position and alternate allele), `genomics/sample` (one per sample column or table row), `genomics/gene`, `genomics/cohort`. Every row is in the contract of its kind (`modules/genomics/packs/genomics/contracts/<kind>.yaml`; the key is `VARIANT_ID`, `SAMPLE_ID`, `GENE` or `COHORT_ID`; numbers are numbers, lists are lists). Column names match without regard to case; CSV may be comma, tab or semicolon separated; a column the contract does not list is kept. A table's name must start with `gene`, `sample` or `cohort`; a VCF ends in `.vcf` or `.vcf.gz`.

**A real example** (the fixture `trio_2026-11-26.vcf`, a trio of samples with `##SAMPLE` lines):

```text
##fileformat=VCFv4.2
##reference=GRCh38
##INFO=<ID=GENE,Number=1,Type=String,Description="Gene symbol">
##FORMAT=<ID=GT,Number=1,Type=String,Description="Genotype">
##SAMPLE=<ID=MOM,Cohort=FAM1,Sex=F,Population=EUR>
##SAMPLE=<ID=DAD,Cohort=FAM1,Sex=M,Population=EUR>
##SAMPLE=<ID=KID,Cohort=FAM1,Sex=F,Population=EUR>
#CHROM	POS	ID	REF	ALT	QUAL	FILTER	INFO	FORMAT	MOM	DAD	KID
17	43094820	rs111	TT	T	99	PASS	GENE=BRCA1;CSQ=frameshift_variant;IMPACT=HIGH;AAPOS=237	GT	0/1	0/0	0/1
17	7676154	.	G	C,T	50.5	PASS	GENE=TP53;CSQ=missense_variant;IMPACT=MODERATE;AAPOS=72	GT:DP	1/2:30	0/0:28	./.:0
13	32340300	.	A	G	.	LowQual	GENE=BRCA2	GT	0/0	0/0	1|1
```

and `genes_2026-11-26.csv`:

```text
gene,chrom,start,protein_length,domains
MLH1,3,36993350,756,"DNA mismatch repair"
APC,5,112707498,2843,
BADGENE,3,notanumber,10,
```

**Source and licence.** Yours. Drishti stores what you give it; for public variant data use the feeds ([ClinVar](load-clinvar.md): public domain under the NCBI data policy). gnomAD and 1000 Genomes are not feeds: download the slice you want (`bcftools view -r 17:43044295-43125483`) and drop the VCF here.

**Errors and fixes.**

| You see | It means | Do this |
|---|---|---|
| `refused ...: a table's name starts with gene, sample or cohort` | the file's name says neither a VCF nor one of the three tables | rename it, or take it out of the drop folder |
| `reject ... SAMPLE_ID is empty (the key of sample)` | an empty key cell | fix the row; drop the file again |
| `reject ... START: 'x' is not integer` | a cell that does not fit the contract | fix the row |
| exit 3, nothing loaded | nothing was dropped | nothing; the drop folder is `<data>/genomics/inbox` (`GENOMICS_INBOX_DIR` or `--from-dir` names another) |
| *no server jar in .../lib* | the load needs the jar | `python3.13 tools/drishti.py build jar` |

More: [the genomics loader README](../../../tools/loaders/genomics/README.md) and [the genomics module guide](../../../modules/genomics/docs/guide.md).
