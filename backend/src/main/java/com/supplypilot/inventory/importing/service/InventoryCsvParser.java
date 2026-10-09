package com.supplypilot.inventory.importing.service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Parses bounded, UTF-8 CSV input without using file names as a storage path. */
@Component
public class InventoryCsvParser {

    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final List<String> EXPECTED_HEADERS = List.of("material_code", "quantity", "unit");

    public List<ParsedInventoryImportRow> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.FILE_EMPTY);
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank() && !isCsvContentType(contentType)) {
            throw new BusinessException(ErrorCode.INVALID_FILE_FORMAT);
        }

        try (InputStream input = utf8InputWithoutBom(new BoundedInputStream(new BufferedInputStream(file.getInputStream()), MAX_FILE_BYTES));
             Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.RFC4180.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .setAllowMissingColumnNames(false)
                     .get()
                     .parse(reader)) {
            List<String> headers = parser.getHeaderNames();
            if (!EXPECTED_HEADERS.equals(headers)) {
                throw new BusinessException(ErrorCode.INVALID_FILE_FORMAT);
            }

            List<ParsedInventoryImportRow> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                boolean structurallyValid = record.size() == EXPECTED_HEADERS.size();
                rows.add(new ParsedInventoryImportRow(
                        (int) record.getRecordNumber() + 1,
                        value(record, 0),
                        value(record, 1),
                        value(record, 2),
                        structurallyValid
                ));
            }
            if (rows.isEmpty()) {
                throw new BusinessException(ErrorCode.FILE_EMPTY);
            }
            return rows;
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_FILE_FORMAT);
        }
    }

    private static String value(CSVRecord record, int index) {
        return record.size() > index ? record.get(index) : null;
    }

    private static boolean isCsvContentType(String contentType) {
        String normalized = contentType.toLowerCase(java.util.Locale.ROOT);
        return normalized.startsWith("text/csv")
                || normalized.startsWith("application/csv")
                || normalized.startsWith("text/plain")
                || normalized.startsWith("application/octet-stream");
    }

    private static InputStream utf8InputWithoutBom(InputStream input) throws IOException {
        PushbackInputStream pushback = new PushbackInputStream(input, 3);
        byte[] prefix = pushback.readNBytes(3);
        if (prefix.length == 3
                && (prefix[0] & 0xFF) == 0xEF
                && (prefix[1] & 0xFF) == 0xBB
                && (prefix[2] & 0xFF) == 0xBF) {
            return pushback;
        }
        pushback.unread(prefix);
        return pushback;
    }

    private static final class BoundedInputStream extends InputStream {
        private final InputStream delegate;
        private final long limit;
        private long count;

        private BoundedInputStream(InputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                increment(read);
            }
            return read;
        }

        private void increment(int read) throws IOException {
            count += read;
            if (count > limit) {
                throw new IOException("File exceeds upload limit");
            }
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
