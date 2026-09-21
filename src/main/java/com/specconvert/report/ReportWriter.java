package com.specconvert.report;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Common contract for writing a {@link MigrationReport} to disk.
 *
 * <p>Implementations are obtained via {@link #forFormat(String)}.
 */
public interface ReportWriter {

    /**
     * Write {@code report} to the given {@code path}.
     *
     * @param report the completed migration report
     * @param path   destination file; parent directories must already exist
     * @throws IOException if the file cannot be written
     */
    void write(MigrationReport report, Path path) throws IOException;

    /**
     * Return the {@link ReportWriter} for the given format string.
     *
     * @param format {@code "json"} or {@code "markdown"} (case-sensitive)
     * @throws IllegalArgumentException for any other value
     */
    static ReportWriter forFormat(String format) {
        return switch (format) {
            case "json"     -> new JsonReportWriter();
            case "markdown" -> new MarkdownReportWriter();
            default -> throw new IllegalArgumentException(
                    "Unknown report format '" + format + "'. Expected 'json' or 'markdown'.");
        };
    }
}
