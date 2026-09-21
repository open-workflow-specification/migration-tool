package com.specconvert.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Renders a {@link MigrationReport} as a Markdown document and writes it to disk.
 */
public class MarkdownReportWriter implements ReportWriter {

    @Override
    public void write(MigrationReport report, Path path) throws IOException {
        Files.writeString(path, render(report));
    }

    /**
     * Render {@code report} to a Markdown string.
     */
    static String render(MigrationReport report) {
        StringBuilder sb = new StringBuilder();

        // ----------------------------------------------------------------
        // Title
        // ----------------------------------------------------------------
        sb.append("# Migration Report\n\n");

        // ----------------------------------------------------------------
        // Summary table
        // ----------------------------------------------------------------
        MigrationReport.Summary s = report.summary;
        sb.append("## Summary\n\n");
        sb.append("| Field | Value |\n");
        sb.append("|---|---|\n");
        appendRow(sb, "Source file",     s.sourceFile);
        appendRow(sb, "Source version",  s.sourceVersion);
        appendRow(sb, "Target version",  s.targetVersion);
        appendRow(sb, "Migration date",  s.migrationDate);
        appendRow(sb, "Overall status",  s.overallStatus);
        sb.append("\n");

        // ----------------------------------------------------------------
        // Statistics table
        // ----------------------------------------------------------------
        if (s.statistics != null) {
            MigrationReport.Statistics st = s.statistics;
            sb.append("## Statistics\n\n");
            sb.append("| Metric | Count |\n");
            sb.append("|---|---|\n");
            appendRow(sb, "Total states",    String.valueOf(st.totalStates));
            appendRow(sb, "Migrated states", String.valueOf(st.migratedStates));
            appendRow(sb, "Warnings",        String.valueOf(st.warningsCount));
            appendRow(sb, "Errors",          String.valueOf(st.errorsCount));
            sb.append("\n");
        }

        // ----------------------------------------------------------------
        // Issues
        // ----------------------------------------------------------------
        List<MigrationReport.Issue> issues = report.issues;
        sb.append("## Issues\n\n");
        if (issues == null || issues.isEmpty()) {
            sb.append("_No issues recorded._\n\n");
        } else {
            sb.append("| # | Severity | Category | Location | Message | Original | Converted | Action Required |\n");
            sb.append("|---|---|---|---|---|---|---|---|\n");
            for (int i = 0; i < issues.size(); i++) {
                MigrationReport.Issue issue = issues.get(i);
                sb.append("| ").append(i + 1)
                  .append(" | ").append(safe(issue.severity))
                  .append(" | ").append(safe(issue.category))
                  .append(" | ").append(safe(issue.sourceLocation))
                  .append(" | ").append(safe(issue.message))
                  .append(" | ").append(safe(issue.original))
                  .append(" | ").append(safe(issue.converted))
                  .append(" | ").append(safe(issue.actionRequired))
                  .append(" |\n");
            }
            sb.append("\n");
        }

        // ----------------------------------------------------------------
        // Manual migration tasks
        // ----------------------------------------------------------------
        List<MigrationReport.ManualTask> tasks = report.manualTasks;
        sb.append("## Manual Migration Tasks\n\n");
        if (tasks == null || tasks.isEmpty()) {
            sb.append("_No manual tasks recorded._\n\n");
        } else {
            sb.append("| # | Priority | Description | Details | Source Reference |\n");
            sb.append("|---|---|---|---|---|\n");
            for (MigrationReport.ManualTask task : tasks) {
                sb.append("| ").append(task.taskId)
                  .append(" | ").append(safe(task.priority))
                  .append(" | ").append(safe(task.description))
                  .append(" | ").append(safe(task.details))
                  .append(" | ").append(safe(task.sourceReference))
                  .append(" |\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // ----------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------

    private static void appendRow(StringBuilder sb, String field, String value) {
        sb.append("| ").append(field).append(" | ").append(safe(value)).append(" |\n");
    }

    /** Escape pipe characters so they don't break the Markdown table, and handle nulls. */
    private static String safe(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|");
    }
}
