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
# Arrow Flight from R

The guide: [FLIGHT_GUIDE.md](../FLIGHT_GUIDE.md); every request: [FLIGHT_REFERENCE.md](../FLIGHT_REFERENCE.md).

## Install

`install.packages("arrow")`. R's Flight functions call pyarrow through `reticulate`: install Python's `pyarrow` too
(`arrow::install_pyarrow()`). The simplest path is the Python client itself through `reticulate`.

## Connect and read

```r
library(reticulate)
drishti <- import_from_path("drishti_flight", path = "clients/python")
f <- drishti$Flight("grpc+tls://drishti-1.bank.example:18815", token = Sys.getenv("DRISHTI_TOKEN"), ca = "bank-ca.pem")
pnl <- f$measure("var.scenarios", "DESK:RATES")        # a pyarrow.Table
df <- as.data.frame(arrow::as_arrow_table(pnl))
```

(output from the demo lake: to be filled in the heavy run)

## Stream and errors

`f$stream(f$request("measure", "measure('var.scenarios', 'DESK:RATES')"))` is a Python iterator: `iterate(…, function(x) …)` reads it,
each item the sequence and the batch. A refusal is a Python `FlightRefused` raised in R, its message starting with the code.
