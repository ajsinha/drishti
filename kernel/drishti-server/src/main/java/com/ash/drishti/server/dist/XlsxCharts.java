/*
 * Project Drishti · Any data. Any domain. One grammar.
 *
 * Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
 * All rights reserved.
 *
 * PROPRIETARY AND CONFIDENTIAL.
 *
 * This file is the confidential and proprietary property of Ashutosh Sinha.
 * Unauthorised copying, use, modification, distribution or disclosure of this
 * file, via any medium, is strictly prohibited except with the express prior
 * written permission of the copyright holder.
 *
 * See the LICENSE file in the root of this repository for the full terms.
 */
package com.ash.drishti.server.dist;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Native Excel charts for the chart sections of an export: DrawingML chart parts written as plain XML next to the data sheet (bar, column, line, area, pie and
 * doughnut; any other chart visual is drawn as columns). The chart reads the sheet's own cells, so it shows exactly what the sheet shows: a column that is
 * masked for the reader is text in the sheet, so it is never a series. A section with no category column or no numeric column gets no chart, and the data table
 * is always there.
 */
final class XlsxCharts {

    /** At most this many categories are plotted; a chart of more says nothing a table does not. */
    static final int MAX_POINTS = 500;
    /** At most this many series (numeric columns). */
    static final int MAX_SERIES = 6;

    private static final String XML = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
    private static final String C = "http://schemas.openxmlformats.org/drawingml/2006/chart";
    private static final String A = "http://schemas.openxmlformats.org/drawingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String[] COLOURS = {"4E79A7", "F28E2B", "59A14F", "E15759", "76B7B2", "EDC948"};

    /** What to draw for a section: the kind, the numeric columns (the category column is always the first) and how many points. */
    record Plan(String kind, List<Integer> series, boolean dateCategories, int points) {}

    private XlsxCharts() {}

    /** The chart plan for a section, or null when it has no chart (not a chart section, no rows, no plottable column). */
    static Plan plan(ExportDoc.Section s) {
        if (!ExportDoc.CHART.equals(s.type()) || s.rows().isEmpty() || s.columns().size() < 2) {
            return null;
        }
        String kind = kind(s.kind());
        boolean round = "pie".equals(kind) || "doughnut".equals(kind);
        List<Integer> series = new ArrayList<>();
        for (int c = 1; c < s.columns().size() && series.size() < (round ? 1 : MAX_SERIES); c++) {
            if (!s.masked().contains(s.columns().get(c)) && s.isNumeric(c)) {
                series.add(c);
            }
        }
        Object first = firstOf(s, 0);
        if (series.isEmpty() || s.isNumeric(0) && !(first instanceof LocalDate)) {
            return null;
        }
        return new Plan(kind, series, first instanceof LocalDate, Math.min(s.rows().size(), MAX_POINTS));
    }

    private static Object firstOf(ExportDoc.Section s, int column) {
        for (List<Object> r : s.rows()) {
            if (column < r.size() && r.get(column) != null) {
                return r.get(column);
            }
        }
        return null;
    }

    static String kind(String visual) {
        return switch (visual == null ? "" : visual.toLowerCase(Locale.ROOT)) {
            case "bar" -> "bar";
            case "line" -> "line";
            case "area" -> "area";
            case "pie" -> "pie";
            case "donut", "doughnut" -> "doughnut";
            default -> "column";
        };
    }

    /** The drawing part: one chart, anchored just right of the data (starting at column {@code fromCol}, row 1), about 9 columns by 20 rows. */
    static String drawing(int fromCol) {
        return XML + "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"" + A + "\">"
                + "<xdr:twoCellAnchor><xdr:from><xdr:col>" + fromCol + "</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>0</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>"
                + "<xdr:to><xdr:col>" + (fromCol + 9) + "</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>20</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>"
                + "<xdr:graphicFrame macro=\"\"><xdr:nvGraphicFramePr><xdr:cNvPr id=\"2\" name=\"Chart 1\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr>"
                + "<xdr:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm><a:graphic><a:graphicData uri=\"" + C + "\">"
                + "<c:chart xmlns:c=\"" + C + "\" xmlns:r=\"" + R + "\" r:id=\"rId1\"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor></xdr:wsDr>";
    }

    /** The chart part for {@code s}, whose data is on the sheet called {@code sheet} (header in row 1, data from row 2). */
    static String chart(ExportDoc.Section s, Plan p, String sheet) {
        String ref = "'" + sheet.replace("'", "''") + "'!";
        int last = p.points() + 1;
        boolean round = "pie".equals(p.kind()) || "doughnut".equals(p.kind());
        String tag = switch (p.kind()) {
            case "line" -> "lineChart";
            case "area" -> "areaChart";
            case "pie" -> "pieChart";
            case "doughnut" -> "doughnutChart";
            default -> "barChart";
        };
        StringBuilder b = new StringBuilder(XML).append("<c:chartSpace xmlns:c=\"").append(C).append("\" xmlns:a=\"").append(A).append("\" xmlns:r=\"").append(R).append("\">");
        b.append("<c:roundedCorners val=\"0\"/><c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>").append(XlsxWriter.esc(s.title()))
                .append("</a:t></a:r></a:p></c:rich></c:tx><c:overlay val=\"0\"/></c:title><c:autoTitleDeleted val=\"0\"/><c:plotArea><c:layout/>");
        b.append("<c:").append(tag).append('>');
        if (tag.equals("barChart")) {
            b.append("<c:barDir val=\"").append("bar".equals(p.kind()) ? "bar" : "col").append("\"/><c:grouping val=\"clustered\"/>");
        } else if (tag.equals("lineChart") || tag.equals("areaChart")) {
            b.append("<c:grouping val=\"standard\"/>");
        }
        b.append("<c:varyColors val=\"").append(round ? 1 : 0).append("\"/>");
        int n = 0;
        for (int col : p.series()) {
            String letter = XlsxWriter.col(col);
            String colour = COLOURS[n % COLOURS.length];
            b.append("<c:ser><c:idx val=\"").append(n).append("\"/><c:order val=\"").append(n).append("\"/>");
            b.append("<c:tx><c:strRef><c:f>").append(ref).append('$').append(letter).append("$1</c:f></c:strRef></c:tx>");
            if (!round) {
                b.append("<c:spPr>").append(tag.equals("lineChart") ? "<a:ln w=\"28575\"><a:solidFill><a:srgbClr val=\"" + colour + "\"/></a:solidFill></a:ln>"
                        : "<a:solidFill><a:srgbClr val=\"" + colour + "\"/></a:solidFill>").append("</c:spPr>");
            }
            if (tag.equals("barChart")) {
                b.append("<c:invertIfNegative val=\"0\"/>");
            }
            String cat = ref + "$A$2:$A$" + last;
            b.append(p.dateCategories() ? "<c:cat><c:numRef><c:f>" + cat + "</c:f></c:numRef></c:cat>" : "<c:cat><c:strRef><c:f>" + cat + "</c:f></c:strRef></c:cat>");
            b.append("<c:val><c:numRef><c:f>").append(ref).append('$').append(letter).append("$2:$").append(letter).append('$').append(last).append("</c:f></c:numRef></c:val>");
            if (tag.equals("lineChart")) {
                b.append("<c:smooth val=\"0\"/>");
            }
            b.append("</c:ser>");
            n++;
        }
        if (tag.equals("barChart")) {
            b.append("<c:gapWidth val=\"80\"/>");
        }
        if (tag.equals("doughnutChart")) {
            b.append("<c:firstSliceAng val=\"0\"/><c:holeSize val=\"55\"/>");
        } else if (tag.equals("pieChart")) {
            b.append("<c:firstSliceAng val=\"0\"/>");
        }
        if (!round) {
            b.append("<c:axId val=\"111111\"/><c:axId val=\"222222\"/>");
        }
        b.append("</c:").append(tag).append('>');
        if (!round) {
            boolean horizontal = "bar".equals(p.kind());
            b.append("<c:catAx><c:axId val=\"111111\"/><c:scaling><c:orientation val=\"").append(horizontal ? "maxMin" : "minMax").append("\"/></c:scaling><c:delete val=\"0\"/>")
                    .append("<c:axPos val=\"").append(horizontal ? "l" : "b").append("\"/><c:numFmt formatCode=\"").append(p.dateCategories() ? "yyyy-mm-dd" : "General")
                    .append("\" sourceLinked=\"0\"/><c:majorTickMark val=\"none\"/><c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"222222\"/>")
                    .append("<c:crosses val=\"autoZero\"/><c:auto val=\"1\"/><c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/><c:noMultiLvlLbl val=\"0\"/></c:catAx>");
            b.append("<c:valAx><c:axId val=\"222222\"/><c:scaling><c:orientation val=\"minMax\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"").append(horizontal ? "b" : "l")
                    .append("\"/><c:majorGridlines/><c:numFmt formatCode=\"#,##0.##\" sourceLinked=\"0\"/><c:majorTickMark val=\"none\"/><c:minorTickMark val=\"none\"/>")
                    .append("<c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"111111\"/><c:crosses val=\"").append(horizontal ? "max" : "autoZero")
                    .append("\"/><c:crossBetween val=\"between\"/></c:valAx>");
        }
        b.append("</c:plotArea>");
        if (p.series().size() > 1 || round) {
            b.append("<c:legend><c:legendPos val=\"b\"/><c:overlay val=\"0\"/></c:legend>");
        }
        return b.append("<c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/></c:chart></c:chartSpace>").toString();
    }
}
