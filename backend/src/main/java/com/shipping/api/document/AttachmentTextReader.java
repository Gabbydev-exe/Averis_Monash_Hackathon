package com.shipping.api.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class AttachmentTextReader {

    public AttachmentTextResult read(String filename, byte[] bytes) {
        String safeFilename = filename == null ? "" : filename;
        if (bytes == null || bytes.length == 0) {
            return result(safeFilename, AttachmentReadStatus.EMPTY, null,
                    "Attachment is empty.");
        }

        String lower = safeFilename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt")) {
            return readTxt(safeFilename, bytes);
        }
        if (lower.endsWith(".pdf")) {
            return readPdf(safeFilename, bytes);
        }
        if (lower.endsWith(".doc")) {
            try (var extractor = new org.apache.poi.hwpf.extractor.WordExtractor(new ByteArrayInputStream(bytes))) {
                String text = extractor.getText();
                return result(safeFilename, text.isBlank() ? AttachmentReadStatus.EMPTY : AttachmentReadStatus.OK, text, null);
            } catch (Exception e) { return result(safeFilename, AttachmentReadStatus.UNREADABLE, null, "DOC could not be read."); }
        }
        if (lower.endsWith(".docx")) {
            return readDocx(safeFilename, bytes);
        }
        if (lower.endsWith(".xlsx")) {
            return readXlsx(safeFilename, bytes);
        }

        return result(safeFilename, AttachmentReadStatus.UNSUPPORTED, null,
                "Unsupported attachment format. Supported text extraction formats: .txt, .pdf, .doc, .docx, .xlsx.");
    }

    private AttachmentTextResult readTxt(String filename, byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            if (text.isBlank()) {
                return result(filename, AttachmentReadStatus.EMPTY, null,
                        "Attachment contains no text.");
            }
            return result(filename, AttachmentReadStatus.OK, text, null);
        } catch (CharacterCodingException exception) {
            return result(filename, AttachmentReadStatus.UNREADABLE, null,
                    "Text attachment is not valid UTF-8.");
        }
    }

    private AttachmentTextResult readPdf(String filename, byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            String text = new PDFTextStripper().getText(document);
            if (text == null || text.isBlank()) {
                return result(filename, AttachmentReadStatus.UNREADABLE, null,
                        "PDF contains no extractable text; OCR or human review may be required.");
            }
            return result(filename, AttachmentReadStatus.OK, text, null);
        } catch (Exception exception) {
            return result(filename, AttachmentReadStatus.UNREADABLE, null,
                    "PDF could not be read.");
        }
    }

    private AttachmentTextResult readDocx(String filename, byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            String text = extractor.getText();
            if (text == null || text.isBlank()) {
                return result(filename, AttachmentReadStatus.EMPTY, null,
                        "DOCX contains no text.");
            }
            return result(filename, AttachmentReadStatus.OK, text, null);
        } catch (Exception exception) {
            return result(filename, AttachmentReadStatus.UNREADABLE, null,
                    "DOCX could not be read.");
        }
    }

    private AttachmentTextResult readXlsx(String filename, byte[] bytes) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            StringBuilder text = new StringBuilder();

            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);

                if (workbook.getNumberOfSheets() > 1) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append("Sheet: ").append(sheet.getSheetName()).append('\n');
                }

                for (Row row : sheet) {
                    StringBuilder rowText = new StringBuilder();
                    int firstCell = row.getFirstCellNum();
                    int lastCell = row.getLastCellNum();

                    if (firstCell < 0 || lastCell < 0) {
                        continue;
                    }

                    for (int cellIndex = firstCell; cellIndex < lastCell; cellIndex++) {
                        Cell cell = row.getCell(cellIndex, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        String value = cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim();

                        if (rowText.length() > 0) {
                            rowText.append('\t');
                        }
                        rowText.append(value);
                    }

                    String rowValue = rowText.toString().stripTrailing();
                    if (!rowValue.isBlank()) {
                        text.append(rowValue).append('\n');
                    }
                }
            }

            String extracted = text.toString().trim();
            if (extracted.isBlank()) {
                return result(filename, AttachmentReadStatus.EMPTY, null,
                        "XLSX contains no text.");
            }

            return result(filename, AttachmentReadStatus.OK, extracted, null);
        } catch (Exception exception) {
            return result(filename, AttachmentReadStatus.UNREADABLE, null,
                    "XLSX could not be read.");
        }
    }

    private AttachmentTextResult result(
            String filename,
            AttachmentReadStatus status,
            String text,
            String message
    ) {
        return new AttachmentTextResult(filename, status, text, message);
    }
}
