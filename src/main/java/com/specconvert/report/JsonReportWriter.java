package com.specconvert.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes a {@link MigrationReport} to disk as indented JSON.
 */
public class JsonReportWriter implements ReportWriter {

    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void write(MigrationReport report, Path path) throws IOException {
        MAPPER.writeValue(path.toFile(), report);
    }
}
