package ru.cashflow.preprocessor.features.statements.command;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDate;

import javax.xml.parsers.SAXParserFactory;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/** SAX-based worksheet reader: no XSSFWorkbook or full-sheet allocation. */
final class XlsxStreamingRows {

    private XlsxStreamingRows() {}

    static void read(Path path, ExcelRows.Context context) {
        try (OPCPackage pkg = OPCPackage.open(path.toFile())) {
            var strings = new ReadOnlySharedStringsTable(pkg);
            var reader = new XSSFReader(pkg);
            var styles = reader.getStylesTable();
            var factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            var parser = factory.newSAXParser();
            var sheets = (XSSFReader.SheetIterator) reader.getSheetsData();
            while (sheets.hasNext()) {
                try (InputStream data = sheets.next()) {
                    var sheet = context.sheet(sheets.getSheetName());
                    parser.parse(data, new SheetHandler(sheet, strings, styles));
                    sheet.finishSheet();
                }
            }
        } catch (ru.cashflow.preprocessor.features.statements.model.CsvInputException exception) {
            throw exception;
        } catch (Exception exception) {
            Throwable nested = exception;
            while (nested != null) {
                if (nested instanceof ru.cashflow.preprocessor.features.statements.model.CsvInputException issue) {
                    throw issue;
                }
                nested = nested.getCause();
            }
            throw ExcelRows.problem("INVALID_EXCEL", 422, "Не удалось прочитать XLSX", 0);
        }
    }

    private static final class SheetHandler extends DefaultHandler {
        private final ExcelRows.Sheet sheet;
        private final ReadOnlySharedStringsTable strings;
        private final StylesTable styles;
        private final StringBuilder contents = new StringBuilder();
        private int row = -1;
        private int column;
        private int style;
        private String type;
        private String element;
        private boolean formula;
        private boolean cached;

        SheetHandler(ExcelRows.Sheet sheet, ReadOnlySharedStringsTable strings, StylesTable styles) {
            this.sheet = sheet;
            this.strings = strings;
            this.styles = styles;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attrs) {
            if ("row".equals(localName)) {
                row = Integer.parseInt(attrs.getValue("r")) - 1;
                column = 0;
            } else if ("c".equals(localName)) {
                String address = attrs.getValue("r");
                column = address == null ? column : columnIndex(address);
                type = attrs.getValue("t");
                String s = attrs.getValue("s");
                style = s == null ? -1 : Integer.parseInt(s);
                formula = false;
                cached = false;
            } else if ("f".equals(localName)) {
                formula = true;
            } else if ("v".equals(localName) || "t".equals(localName)) {
                contents.setLength(0);
                element = localName;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (element != null) {
                contents.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (localName.equals(element)) {
                if ("v".equals(localName)) {
                    cached = true;
                    sheet.cell(row, column, value(contents.toString()));
                } else if ("t".equals(localName) && ("inlineStr".equals(type) || "str".equals(type))) {
                    cached = true;
                    sheet.cell(row, column, contents.toString());
                }
                element = null;
            } else if ("c".equals(localName)) {
                if (formula && !cached) {
                    throw ExcelRows.problem("UNCACHED_FORMULA", 422,
                            "Формула не содержит сохранённого результата", row + 1L);
                }
                column++;
            }
        }

        private String value(String raw) {
            if ("s".equals(type)) {
                return strings.getItemAt(Integer.parseInt(raw)).getString();
            }
            if ("str".equals(type) || "inlineStr".equals(type) || "e".equals(type)) {
                if ("e".equals(type)) {
                    throw ExcelRows.problem("INVALID_CELL", 422, "Ошибка Excel в ячейке", row + 1L);
                }
                return raw;
            }
            if ("b".equals(type)) {
                return "1".equals(raw) ? "true" : "false";
            }
            if (style >= 0 && styles != null) {
                XSSFCellStyle cellStyle = styles.getStyleAt(style);
                if (DateUtil.isADateFormat(cellStyle.getDataFormat(), cellStyle.getDataFormatString())) {
                    return LocalDate.of(1899, 12, 30).plusDays(new java.math.BigDecimal(raw).longValue()).toString();
                }
            }
            return ExcelRows.number(raw, row + 1L);
        }

        private int columnIndex(String address) {
            int col = 0;
            for (int i = 0; i < address.length(); i++) {
                char c = address.charAt(i);
                if (!Character.isLetter(c)) {
                    break;
                }
                col = col * 26 + Character.toUpperCase(c) - 'A' + 1;
            }
            return col - 1;
        }
    }
}
