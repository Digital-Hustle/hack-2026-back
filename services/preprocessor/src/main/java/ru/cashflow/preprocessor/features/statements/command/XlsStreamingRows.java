package ru.cashflow.preprocessor.features.statements.command;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.poi.hssf.eventusermodel.HSSFEventFactory;
import org.apache.poi.hssf.eventusermodel.HSSFListener;
import org.apache.poi.hssf.eventusermodel.HSSFRequest;
import org.apache.poi.hssf.record.BOFRecord;
import org.apache.poi.hssf.record.BoolErrRecord;
import org.apache.poi.hssf.record.FormulaRecord;
import org.apache.poi.hssf.record.LabelRecord;
import org.apache.poi.hssf.record.LabelSSTRecord;
import org.apache.poi.hssf.record.NumberRecord;
import org.apache.poi.hssf.record.RKRecord;
import org.apache.poi.hssf.record.Record;
import org.apache.poi.hssf.record.SSTRecord;
import org.apache.poi.hssf.record.StringRecord;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

/** Legacy XLS event reader; never materializes an HSSFWorkbook. */
final class XlsStreamingRows {

    private XlsStreamingRows() {}

    static void read(Path path, ExcelRows.Context context) {
        try (var stream = Files.newInputStream(path); var fs = new POIFSFileSystem(stream)) {
            var listener = new Reader(context);
            HSSFRequest request = new HSSFRequest();
            request.addListenerForAllRecords(listener);
            new HSSFEventFactory().processWorkbookEvents(request, fs);
            listener.finish();
        } catch (ru.cashflow.preprocessor.features.statements.model.CsvInputException exception) {
            throw exception;
        } catch (Exception exception) {
            throw ExcelRows.problem("INVALID_EXCEL", 422, "Не удалось прочитать XLS", 0);
        }
    }

    private static final class Reader implements HSSFListener {
        private final ExcelRows.Context context;
        private ExcelRows.Sheet sheet;
        private SSTRecord strings;
        private int sheetNumber;
        private FormulaRecord pendingString;

        Reader(ExcelRows.Context context) {
            this.context = context;
        }

        @Override
        public void processRecord(Record record) {
            if (record instanceof SSTRecord sst) {
                strings = sst;
            } else if (record instanceof BOFRecord bof && bof.getType() == BOFRecord.TYPE_WORKSHEET) {
                if (sheet != null) {
                    sheet.finishSheet();
                }
                sheet = context.sheet("Sheet" + ++sheetNumber);
            } else if (sheet != null) {
                if (record instanceof LabelSSTRecord label) {
                    sheet.cell(label.getRow(), label.getColumn(),
                            strings.getString(label.getSSTIndex()).getString());
                } else if (record instanceof LabelRecord label) {
                    sheet.cell(label.getRow(), label.getColumn(), label.getValue());
                } else if (record instanceof NumberRecord num) {
                    sheet.cell(num.getRow(), num.getColumn(),
                            ExcelRows.number(Double.toString(num.getValue()), num.getRow() + 1L));
                } else if (record instanceof RKRecord num) {
                    sheet.cell(num.getRow(), num.getColumn(),
                            ExcelRows.number(Double.toString(num.getRKNumber()), num.getRow() + 1L));
                } else if (record instanceof FormulaRecord formula) {
                    if (formula.hasCachedResultString()) {
                        pendingString = formula;
                    } else {
                        sheet.cell(formula.getRow(), formula.getColumn(),
                                ExcelRows.number(Double.toString(formula.getValue()), formula.getRow() + 1L));
                    }
                } else if (record instanceof StringRecord text && pendingString != null) {
                    sheet.cell(pendingString.getRow(), pendingString.getColumn(), text.getString());
                    pendingString = null;
                } else if (record instanceof BoolErrRecord value) {
                    if (value.isError()) {
                        throw ExcelRows.problem("INVALID_CELL", 422, "Ошибка Excel в ячейке", value.getRow() + 1L);
                    }
                    sheet.cell(value.getRow(), value.getColumn(), Boolean.toString(value.getBooleanValue()));
                }
            }
        }

        void finish() {
            if (pendingString != null) {
                throw ExcelRows.problem("UNCACHED_FORMULA", 422, "Формула не содержит сохранённого результата",
                        pendingString.getRow() + 1L);
            }
            if (sheet != null) {
                sheet.finishSheet();
            }
        }
    }
}
