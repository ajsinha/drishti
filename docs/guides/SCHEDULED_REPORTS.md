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
# Reports: scheduled searches, export and subscriptions

The **Reports** page (`Views`, `Reports`, `/reports`) lists every scheduled report and subscription you have, with a box to find one, and links to your alerts and the BI reports. Learn it by doing in [tutorial 9](../tutorials/09-reports.md) (scheduled searches) and [tutorial 11](../tutorials/11-bi-that-runs-itself.md) (BI subscriptions, alerts, export); this page is the reference.

## Scheduled reports

A report is a search that runs on its own, on a schedule, and delivers its result as a CSV file or as JSON Lines (one entity a
line, keyed as the CSV's header; [DATA_FORMATS.md](DATA_FORMATS.md)): **Format** in the dialog, `format: csv | jsonl` over the API. On the search page,
after a search, press **Schedule…** (or open **Reports** in the top bar). Give it a name, check the search, and say when:

| When | Runs |
|---|---|
| `business-days 18:30` | at 18:30 on weekdays that are not holidays in the server's calendar |
| `weekdays 07:00` | at 07:00 Monday to Friday |
| `daily 06:00` | every day at 06:00 |
| `hourly` | on the hour |
| `cron 0 0 8,12,16 * * MON-FRI` | a cron expression: second, minute, hour, day, month, weekday |

Times are in the business-date zone (New York by default). **Data for** chooses the current business day or the
one before (for a morning report on yesterday's close). **Deliver to** is the server's reports folder, or a
webhook (a URL your administrator allows; the CSV is posted as `text/csv`, JSON Lines as `application/jsonl`, with `X-Drishti-Report`
and `X-Drishti-Business-Date` headers); a folder delivery is a `.csv` or a `.jsonl` file. Email needs SMTP settings on the server and is not offered until then.

A report runs **as you**: it sees what your roles let you see when it runs, with the same masked fields. If your
account is disabled or deleted, it stops. The list shows each report's next run and its last run (rows, where the
file went, or the error); **Run now** runs one at once. The walk-through (name, search, when, deliver to, and the file that appears) is [tutorial 9](../tutorials/09-reports.md).

### Export, and subscriptions

**Export.** On a BI report, *Export* under the page tabs saves **this page** or one visual as **PDF**, a **PNG** picture, an **Excel** workbook (a sheet per visual, numbers as numbers), **CSV** or **JSON Lines** (a row a line, the CSV's column names as keys). On a
view, *Export* in the header does the same for the view. What you save is what **you** may see: your masks and your row rules apply, and the slicers, controls and filters you set are in it, written in the header with the
business date. A file made for you can be shared; it holds no one else's numbers.

**Subscribe.** *Subscribe* (next to Export) sends the report or view to you by email, on a schedule: **Send** offers every day, every business day (07:30 to start with), every week on a day,
the last business day of the month and every hour, at a time you pick; *on a schedule I write (advanced)* and **Advanced: the schedule as text** take any schedule, such as `after-load trade`
(**after the day's data lands**). You choose the form (the tables in the email, a PDF, an Excel file or a JSON Lines file attached). A BI author or an administrator can also subscribe other people or a role; each of them receives a render made for
them. Your subscriptions are on **Reports** in the top bar, in one list with your scheduled reports: *Run now*, *Pause*, *Resume*, *Delete*, and *History* (who was sent what, and if it failed, why). Reference:
[DISTRIBUTION.md](DISTRIBUTION.md); walk-through: [tutorial 11](../tutorials/11-bi-that-runs-itself.md).

