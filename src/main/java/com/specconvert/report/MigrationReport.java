package com.specconvert.report;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Data model for the migration report written alongside the converted workflow.
 * Serialised to JSON by SpecConvert after conversion completes.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MigrationReport {

    @JsonProperty("migration_summary")
    public Summary summary = new Summary();

    @JsonProperty("issues")
    public List<Issue> issues = new ArrayList<>();

    @JsonProperty("manual_migration_tasks")
    public List<ManualTask> manualTasks = new ArrayList<>();

    // ------------------------------------------------------------------
    // Summary
    // ------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Summary {
        @JsonProperty("source_file")      public String sourceFile;
        @JsonProperty("source_version")   public String sourceVersion = "0.8";
        @JsonProperty("target_version")   public String targetVersion = "1.0.0";
        @JsonProperty("migration_date")   public String migrationDate;
        @JsonProperty("overall_status")   public String overallStatus = "success";
        @JsonProperty("statistics")       public Statistics statistics = new Statistics();
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Statistics {
        @JsonProperty("total_states")     public int totalStates;
        @JsonProperty("migrated_states")  public int migratedStates;
        @JsonProperty("warnings_count")   public int warningsCount;
        @JsonProperty("errors_count")     public int errorsCount;
    }

    // ------------------------------------------------------------------
    // Issue
    // ------------------------------------------------------------------

    public enum Severity { INFO, WARNING, ERROR }

    public enum Category {
        expression_conversion,
        state_transformation,
        data_flow,
        error_handling,
        authentication,
        unsupported_feature,
        extension,
        validation
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Issue {
        @JsonProperty("severity")         public String severity;
        @JsonProperty("category")         public String category;
        @JsonProperty("source_location")  public String sourceLocation;
        @JsonProperty("message")          public String message;
        @JsonProperty("original")         public String original;
        @JsonProperty("converted")        public String converted;
        @JsonProperty("action_required")  public String actionRequired;

        public Issue(Severity severity, Category category, String sourceLocation,
                     String message, String original, String converted, String actionRequired) {
            this.severity        = severity.name();
            this.category        = category.name();
            this.sourceLocation  = sourceLocation;
            this.message         = message;
            this.original        = original;
            this.converted       = converted;
            this.actionRequired  = actionRequired;
        }
    }

    // ------------------------------------------------------------------
    // ManualTask
    // ------------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ManualTask {
        @JsonProperty("task_id")          public int taskId;
        @JsonProperty("priority")         public String priority;
        @JsonProperty("description")      public String description;
        @JsonProperty("details")          public String details;
        @JsonProperty("source_reference") public String sourceReference;

        public ManualTask(int taskId, String priority, String description,
                          String details, String sourceReference) {
            this.taskId          = taskId;
            this.priority        = priority;
            this.description     = description;
            this.details         = details;
            this.sourceReference = sourceReference;
        }
    }
}
