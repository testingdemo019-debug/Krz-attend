package com.krztechnologies.attend.common.export;

import com.krztechnologies.attend.common.audit.AuditCategory;
import com.krztechnologies.attend.common.audit.AuditService;
import com.krztechnologies.attend.common.time.TimeService;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Every CSV download ({@code GET …/export}): UTF-8 with a BOM (Excel), formula injection neutralised, the file name
 * {@code <name>-yyyy-MM-dd-HHmm.csv} in the organisation's time zone, {@code no-store}, and an audit entry (category
 * Export). The endpoint itself requires the list's permission AND {@code events.export}.
 */
@Service
public class CsvExportService {

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm");
    /** UTF-8 byte order mark, so Excel opens the file as UTF-8. */
    private static final char BOM = 0xFEFF;

    private final AuditService audit;
    private final TimeService time;

    public CsvExportService(AuditService audit, TimeService time) {
        this.audit = audit;
        this.time = time;
    }

    /** One column: its header and how a row becomes the cell (null → empty). */
    public record Column<T>(String header, Function<T, ?> value) {}

    /**
     * @param name file name stem, e.g. {@code events} → {@code events-2026-10-12-1530.csv}
     * @param what audit target, e.g. "Events (12 Oct – 13 Oct)"
     */
    @Transactional
    public <T> ResponseEntity<StreamingResponseBody> export(
            String name, String what, List<Column<T>> columns, List<T> rows) {
        String fileName = name + "-" + time.zoned(time.now()).format(FILE_STAMP) + ".csv";
        audit.record(AuditCategory.EXPORT, "Exported CSV", what, null, rows.size() + " rows", Map.of("file", fileName));
        StreamingResponseBody body = out -> {
            Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            writer.write(BOM);
            writeLine(writer, columns.stream().map(Column::header).toList());
            for (T row : rows) {
                writeLine(
                        writer, columns.stream().map(c -> c.value().apply(row)).toList());
            }
            writer.flush();
        };
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(fileName)
                                .build()
                                .toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static void writeLine(Writer writer, List<?> cells) throws IOException {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                writer.write(',');
            }
            writer.write(cell(cells.get(i)));
        }
        writer.write("\r\n");
    }

    /**
     * One CSV cell: a text starting with {@code = + - @}, tab or carriage return is prefixed with an apostrophe so a
     * spreadsheet never runs it as a formula; quotes, commas and line breaks are quoted.
     */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (!(value instanceof Number) && !text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains("\"") || text.contains(",") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
