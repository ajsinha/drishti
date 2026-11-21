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
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Before/after comparison of the workbooks Drishti writes (G4 pass 3). Both folders hold the same file names; each pair is read with
 * Apache POI and compared cell by cell (type, value, formula, cached result), then sheet by sheet (names and order, merges, freeze pane,
 * column widths, outline levels, drop-downs, conditional formats, print set-up, tab colour, charts) and by number format, font and fill of
 * each cell. Run: {@code java -cp "<poi jars>" XlsxCompare.java <before dir> <after dir>}. Output: per file the counts of each kind of
 * difference with examples; at the end the totals by kind.
 */
public class XlsxCompare {
    static final Map<String, Integer> TOTAL = new TreeMap<>();
    static final Map<String, List<String>> EXAMPLES = new TreeMap<>();

    public static void main(String[] a) throws Exception {
        System.setProperty("jdk.xml.maxGeneralEntitySizeLimit", "0");          // a big sheet is one large element
        System.setProperty("jdk.xml.totalEntitySizeLimit", "0");
        System.setProperty("jdk.xml.entityExpansionLimit", "0");
        org.apache.poi.util.IOUtils.setByteArrayMaxOverride(800_000_000);
        org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(0);
        File[] before = new File(a[0]).listFiles((d, n) -> n.endsWith(".xlsx"));
        java.util.Arrays.sort(before);
        int same = 0;
        int diff = 0;
        int missing = 0;
        for (File b : before) {
            File n = new File(a[1], b.getName());
            if (!n.exists()) {
                System.out.println("MISSING AFTER " + b.getName());
                missing++;
                continue;
            }
            Map<String, List<String>> d = new LinkedHashMap<>();
            try (XSSFWorkbook x = new XSSFWorkbook(new FileInputStream(b)); XSSFWorkbook y = new XSSFWorkbook(new FileInputStream(n))) {
                compare(x, y, d);
            } catch (Exception e) {
                d.computeIfAbsent("READ_ERROR", k -> new ArrayList<>()).add(String.valueOf(e));
            }
            if (d.isEmpty()) {
                same++;
                continue;
            }
            diff++;
            System.out.println("== " + b.getName());
            d.forEach((k, v) -> {
                System.out.println("   " + k + " x" + v.size() + "   e.g. " + v.stream().limit(3).toList());
                TOTAL.merge(k, v.size(), Integer::sum);
                List<String> ex = EXAMPLES.computeIfAbsent(k, kk -> new ArrayList<>());
                if (ex.size() < 3) {
                    ex.add(b.getName() + ": " + v.get(0));
                }
            });
        }
        for (File n : new File(a[1]).listFiles((d, nm) -> nm.endsWith(".xlsx"))) {
            if (!new File(a[0], n.getName()).exists()) {
                System.out.println("NEW ONLY " + n.getName());
            }
        }
        System.out.println();
        System.out.println("files before " + before.length + ": identical in everything compared " + same + ", with differences " + diff + ", missing after " + missing);
        TOTAL.forEach((k, v) -> System.out.println("  " + k + " x" + v + "   " + EXAMPLES.get(k)));
    }

    static void add(Map<String, List<String>> d, String kind, String what) {
        d.computeIfAbsent(kind, k -> new ArrayList<>()).add(what);
    }

    static void compare(XSSFWorkbook x, XSSFWorkbook y, Map<String, List<String>> d) {
        List<String> nx = names(x);
        List<String> ny = names(y);
        if (!nx.equals(ny)) {
            add(d, "SHEET_NAMES", nx + " -> " + ny);
        }
        if (x.getActiveSheetIndex() != y.getActiveSheetIndex()) {
            add(d, "ACTIVE_TAB", x.getActiveSheetIndex() + " -> " + y.getActiveSheetIndex());
        }
        List<String> dx = definedNames(x);
        List<String> dy = definedNames(y);
        if (!dx.equals(dy)) {
            add(d, "DEFINED_NAMES", dx + " -> " + dy);
        }
        for (int i = 0; i < Math.min(x.getNumberOfSheets(), y.getNumberOfSheets()); i++) {
            sheet((XSSFSheet) x.getSheetAt(i), (XSSFSheet) y.getSheetAt(i), d, x.getSheetName(i));
        }
        String px = x.getProperties().getCoreProperties().getTitle() + "|" + x.getProperties().getCoreProperties().getCreator();
        String py = y.getProperties().getCoreProperties().getTitle() + "|" + y.getProperties().getCoreProperties().getCreator();
        px = norm(px);
        py = norm(py);
        if (!px.equals(py)) {
            add(d, "DOC_PROPERTIES", px + " -> " + py);
        }
        if (x.getCTWorkbook().isSetCalcPr() != y.getCTWorkbook().isSetCalcPr()) {
            add(d, "CALC_ON_LOAD", x.getCTWorkbook().isSetCalcPr() + " -> " + y.getCTWorkbook().isSetCalcPr());
        }
    }

    static List<String> names(XSSFWorkbook w) {
        List<String> n = new ArrayList<>();
        for (int i = 0; i < w.getNumberOfSheets(); i++) {
            n.add(w.getSheetName(i));
        }
        return n;
    }

    static List<String> definedNames(XSSFWorkbook w) {
        List<String> n = new ArrayList<>();
        w.getAllNames().forEach(nm -> n.add(nm.getNameName() + "=" + nm.getRefersToFormula()));
        n.sort(Comparator.naturalOrder());
        return n;
    }

    static void sheet(XSSFSheet x, XSSFSheet y, Map<String, List<String>> d, String name) {
        String at = name + "!";
        int last = Math.max(x.getLastRowNum(), y.getLastRowNum());
        for (int r = 0; r <= last; r++) {
            Row rx = x.getRow(r);
            Row ry = y.getRow(r);
            if (rx != null && ry != null && rx.getOutlineLevel() != ry.getOutlineLevel()) {
                add(d, "ROW_OUTLINE", at + (r + 1) + " " + rx.getOutlineLevel() + " -> " + ry.getOutlineLevel());
            }
            int lc = Math.max(rx == null ? -1 : rx.getLastCellNum(), ry == null ? -1 : ry.getLastCellNum());
            for (int c = 0; c < lc; c++) {
                XSSFCell cx = rx == null ? null : (XSSFCell) rx.getCell(c);
                XSSFCell cy = ry == null ? null : (XSSFCell) ry.getCell(c);
                cell(cx, cy, d, at + ref(c, r));
            }
        }
        if (!x.getMergedRegions().stream().map(CellRangeAddress::formatAsString).sorted().toList()
                .equals(y.getMergedRegions().stream().map(CellRangeAddress::formatAsString).sorted().toList())) {
            add(d, "MERGES", at + x.getMergedRegions() + " -> " + y.getMergedRegions());
        }
        String fx = String.valueOf(x.getPaneInformation() == null ? "none" : x.getPaneInformation().getHorizontalSplitPosition() + "," + x.getPaneInformation().getVerticalSplitPosition());
        String fy = String.valueOf(y.getPaneInformation() == null ? "none" : y.getPaneInformation().getHorizontalSplitPosition() + "," + y.getPaneInformation().getVerticalSplitPosition());
        if (!fx.equals(fy)) {
            add(d, "FREEZE", at + fx + " -> " + fy);
        }
        int cols = Math.max(x.getRow(0) == null ? 0 : x.getRow(0).getLastCellNum(), 40);
        for (int c = 0; c < cols; c++) {
            if (x.getColumnWidth(c) != y.getColumnWidth(c)) {
                add(d, "COL_WIDTH", at + ref(c, 0).replaceAll("\\d", "") + " " + x.getColumnWidth(c) / 256.0 + " -> " + y.getColumnWidth(c) / 256.0);
            }
        }
        if (x.getDataValidations().size() != y.getDataValidations().size()) {
            add(d, "VALIDATIONS", at + x.getDataValidations().size() + " -> " + y.getDataValidations().size());
        } else {
            for (int i = 0; i < x.getDataValidations().size(); i++) {
                String vx = regions(x.getDataValidations().get(i).getRegions()) + String.join(",", x.getDataValidations().get(i).getValidationConstraint().getExplicitListValues() == null ? new String[0] : x.getDataValidations().get(i).getValidationConstraint().getExplicitListValues());
                String vy = regions(y.getDataValidations().get(i).getRegions()) + String.join(",", y.getDataValidations().get(i).getValidationConstraint().getExplicitListValues() == null ? new String[0] : y.getDataValidations().get(i).getValidationConstraint().getExplicitListValues());
                if (!vx.equals(vy)) {
                    add(d, "VALIDATIONS", at + vx + " -> " + vy);
                }
            }
        }
        int cfx = x.getSheetConditionalFormatting().getNumConditionalFormattings();
        int cfy = y.getSheetConditionalFormatting().getNumConditionalFormattings();
        if (cfx != cfy) {
            add(d, "COND_FORMATS", at + cfx + " -> " + cfy);
        }
        String px = x.getPrintSetup().getLandscape() + "/" + x.getPrintSetup().getPaperSize() + "/" + x.getFitToPage() + "/" + x.getMargin(Sheet.TopMargin) + "/" + x.getMargin(Sheet.LeftMargin)
                + "/" + x.getFooter().getLeft() + "|" + x.getFooter().getCenter() + "|" + x.getFooter().getRight() + "/" + x.getHeader().getCenter();
        String py = y.getPrintSetup().getLandscape() + "/" + y.getPrintSetup().getPaperSize() + "/" + y.getFitToPage() + "/" + y.getMargin(Sheet.TopMargin) + "/" + y.getMargin(Sheet.LeftMargin)
                + "/" + y.getFooter().getLeft() + "|" + y.getFooter().getCenter() + "|" + y.getFooter().getRight() + "/" + y.getHeader().getCenter();
        if (!px.equals(py)) {
            add(d, "PRINT_SETUP", at + px + " -> " + py);
        }
        if (x.getRepeatingRows() != null || y.getRepeatingRows() != null) {
            String rx = String.valueOf(x.getRepeatingRows());
            String ry = String.valueOf(y.getRepeatingRows());
            if (!rx.equals(ry)) {
                add(d, "PRINT_TITLES", at + rx + " -> " + ry);
            }
        }
        String tx = String.valueOf(x.getCTWorksheet().getSheetPr() != null && x.getCTWorksheet().getSheetPr().isSetTabColor() ? hex(x.getCTWorksheet().getSheetPr().getTabColor().getRgb()) : null);
        String ty = String.valueOf(y.getCTWorksheet().getSheetPr() != null && y.getCTWorksheet().getSheetPr().isSetTabColor() ? hex(y.getCTWorksheet().getSheetPr().getTabColor().getRgb()) : null);
        if (!tx.equals(ty)) {
            add(d, "TAB_COLOUR", at + tx + " -> " + ty);
        }
        if (x.getRowSumsBelow() != y.getRowSumsBelow()) {
            add(d, "OUTLINE_SUMMARY_BELOW", at + x.getRowSumsBelow() + " -> " + y.getRowSumsBelow());
        }
        if (x.isSelected() != y.isSelected()) {
            add(d, "TAB_SELECTED", at + x.isSelected() + " -> " + y.isSelected());
        }
        if (x.getDefaultRowHeightInPoints() != y.getDefaultRowHeightInPoints()) {
            add(d, "DEFAULT_ROW_HEIGHT", at + x.getDefaultRowHeightInPoints() + " -> " + y.getDefaultRowHeightInPoints());
        }
        List<String> chx = charts(x);
        List<String> chy = charts(y);
        if (!chx.equals(chy)) {
            add(d, "CHARTS", at + chx + " -> " + chy);
        }
    }

    /** The branding a test installs is a global of the test JVM: the product's name there is whichever test ran last. */
    static String norm(String s) {
        if (s == null) {
            return null;
        }
        return s.replace("TestProduct", "Drishti")
                .replaceAll("\\d{4}-\\d\\d-\\d\\d[T ]\\d\\d:\\d\\d(:\\d\\d(\\.\\d+)?)?Z?( UTC)?", "<time>")      // a run's own clock
                .replaceAll("[A-Z]{2}-[0-9A-F]{8}-[0-9A-Z]{8}", "<run>")                                               // a run's id
                .replaceAll("(?<=[:=-])[0-9a-fA-F]{8,}", "<id>")                                                      // data versions' hashes
                .replaceAll("^Data version: .*", "Data version");                                                    // the order of a set of lakes
    }

    /** The capacity workbook's measured figures (Inputs, column B from row 44) are read from the running JVM: they differ every run. */
    static boolean live(String at) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^Inputs!B(\\d+)$").matcher(at);
        return m.matches() && Integer.parseInt(m.group(1)) >= 44;
    }

    static String hex(byte[] b) {
        return b == null ? "null" : java.util.HexFormat.of().formatHex(b);
    }

    static String regions(org.apache.poi.ss.util.CellRangeAddressList l) {
        List<String> out = new ArrayList<>();
        for (CellRangeAddress r : l.getCellRangeAddresses()) {
            out.add(r.formatAsString());
        }
        return out.toString();
    }

    static List<String> charts(XSSFSheet s) {
        List<String> out = new ArrayList<>();
        if (s.getDrawingPatriarch() != null) {
            s.getDrawingPatriarch().getCharts().forEach(ch -> {
                String xml = ch.getCTChart().xmlText();
                String kind = xml.replaceAll("(?s).*<c:(bar|line|area|pie|doughnut)Chart>.*", "$1");
                if (kind.length() > 12) {
                    kind = "?";
                }
                String dir = xml.contains("barDir val=\"bar\"") ? "horizontal" : "";
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("<c:f>([^<]*)</c:f>").matcher(xml);
                List<String> refs = new ArrayList<>();
                while (m.find()) {
                    refs.add(m.group(1));
                }
                out.add(kind + dir + " " + refs);
            });
        }
        return out;
    }

    static String ref(int c, int r) {
        return org.apache.poi.ss.util.CellReference.convertNumToColString(c) + (r + 1);
    }

    static boolean empty(XSSFCell c) {
        return c == null || c.getCellType() == CellType.BLANK && c.getCellStyle().getIndex() == 0;
    }

    static void cell(XSSFCell x, XSSFCell y, Map<String, List<String>> d, String at) {
        boolean ex = empty(x);
        boolean ey = empty(y);
        if (ex && ey) {
            return;
        }
        if (ex != ey) {
            add(d, "CELL_PRESENCE", at + " " + describe(x) + " -> " + describe(y));
            return;
        }
        if (x.getCellType() != y.getCellType()) {
            add(d, "CELL_TYPE", at + " " + describe(x) + " -> " + describe(y));
            return;
        }
        switch (x.getCellType()) {
            case FORMULA -> {
                if (!x.getCellFormula().equals(y.getCellFormula())) {
                    add(d, "FORMULA_TEXT", at + " " + x.getCellFormula() + " -> " + y.getCellFormula());
                }
                if (x.getCachedFormulaResultType() != y.getCachedFormulaResultType()) {
                    add(d, "FORMULA_CACHED_TYPE", at + " " + describe(x) + " -> " + describe(y));
                } else if (!norm(cached(x)).equals(norm(cached(y)))) {
                    add(d, "FORMULA_CACHED_VALUE", at + " " + cached(x) + " -> " + cached(y));
                }
            }
            case NUMERIC -> {
                if (Double.compare(x.getNumericCellValue(), y.getNumericCellValue()) != 0 && !live(at)) {
                    add(d, "NUMBER_VALUE", at + " " + x.getNumericCellValue() + " -> " + y.getNumericCellValue());
                }
            }
            case STRING -> {
                if (!norm(x.getStringCellValue()).equals(norm(y.getStringCellValue()))) {
                    add(d, "TEXT_VALUE", at + " '" + x.getStringCellValue() + "' -> '" + y.getStringCellValue() + "'");
                }
            }
            case BOOLEAN -> {
                if (x.getBooleanCellValue() != y.getBooleanCellValue()) {
                    add(d, "BOOLEAN_VALUE", at);
                }
            }
            case ERROR -> {
                if (x.getErrorCellValue() != y.getErrorCellValue()) {
                    add(d, "ERROR_VALUE", at);
                }
            }
            default -> { }
        }
        XSSFCellStyle sx = x.getCellStyle();
        XSSFCellStyle sy = y.getCellStyle();
        String fx = sx.getDataFormatString();
        String fy = sy.getDataFormatString();
        if (!fx.equals(fy)) {
            add(d, "STYLE_NUMBER_FORMAT", at + " " + fx + " -> " + fy);
        }
        String ox = font(sx);
        String oy = font(sy);
        if (!ox.equals(oy)) {
            add(d, "STYLE_FONT", at + " " + ox + " -> " + oy);
        }
        String gx = String.valueOf(sx.getFillForegroundColorColor() == null ? null : sx.getFillForegroundColorColor().getARGBHex()) + sx.getFillPattern();
        String gy = String.valueOf(sy.getFillForegroundColorColor() == null ? null : sy.getFillForegroundColorColor().getARGBHex()) + sy.getFillPattern();
        if (!gx.equals(gy)) {
            add(d, "STYLE_FILL", at + " " + gx + " -> " + gy);
        }
    }

    static String font(XSSFCellStyle s) {
        var f = s.getFont();
        return (f.getBold() ? "bold " : "") + (f.getItalic() ? "italic " : "") + f.getFontHeightInPoints() + "pt " + (f.getXSSFColor() == null ? "" : f.getXSSFColor().getARGBHex());
    }

    static String cached(XSSFCell c) {
        return switch (c.getCachedFormulaResultType()) {
            case NUMERIC -> String.valueOf(c.getNumericCellValue());
            case STRING -> c.getStringCellValue();
            case BOOLEAN -> String.valueOf(c.getBooleanCellValue());
            case ERROR -> "#err" + c.getErrorCellValue();
            default -> "";
        };
    }

    static String describe(Cell c) {
        if (c == null) {
            return "(none)";
        }
        return c.getCellType() + (c.getCellType() == CellType.FORMULA ? "[" + c.getCachedFormulaResultType() + "]" : "");
    }
}
