package org.openworkflow.migrationtool;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openworkflow.migrationtool.report.MigrationReport;
import org.openworkflow.migrationtool.report.ReportCollector;
import org.openworkflow.migrationtool.report.ReportWriter;
import org.openworkflow.migrationtool.validator.OutputValidator;
import org.openworkflow.migrationtool.validator.ValidationResult;
import org.openworkflow.migrationtool.transformer.Callback;
import org.openworkflow.migrationtool.transformer.Event;
import org.openworkflow.migrationtool.transformer.ForEach;
import org.openworkflow.migrationtool.transformer.Inject;
import org.openworkflow.migrationtool.transformer.Operation;
import org.openworkflow.migrationtool.transformer.Parallel;
import org.openworkflow.migrationtool.transformer.Sleep;
import org.openworkflow.migrationtool.transformer.Switch;
import org.openworkflow.migrationtool.transformer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// 0.8
import io.serverlessworkflow.api.mapper.JsonObjectMapper;
import io.serverlessworkflow.api.mapper.YamlObjectMapper;
import io.serverlessworkflow.api.states.CallbackState;
import io.serverlessworkflow.api.states.EventState;
import io.serverlessworkflow.api.states.ForEachState;
import io.serverlessworkflow.api.states.InjectState;
import io.serverlessworkflow.api.states.ParallelState;
import io.serverlessworkflow.api.states.OperationState;
import io.serverlessworkflow.api.states.SleepState;
import io.serverlessworkflow.api.states.SwitchState;
import io.serverlessworkflow.api.interfaces.State;

// 1.0
import io.serverlessworkflow.api.types.Document;
import io.serverlessworkflow.api.types.DurationInline;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskItem;
import jakarta.validation.constraints.Null;
import io.serverlessworkflow.api.WorkflowFormat;
import io.serverlessworkflow.api.WorkflowWriter;
import java.util.Map;

// Workflow10 = io.serverlessworkflow.api.types.Workflow  (1.0 output)
// Workflow08 = io.serverlessworkflow.api.Workflow        (0.8 input, referenced by FQN)

/** MixIn that suppresses zero-valued fields on DurationInline during serialisation. */
@JsonInclude(JsonInclude.Include.NON_DEFAULT)
interface DurationInlineMixIn {}

/**
 * SpecConvert — CNCF Serverless Workflow spec 0.8 -> 1.0 converter.
 *
 * Input  is parsed via the 0.8 SDK (serverlessworkflow-api 4.1.0.Final).
 * Output is built via the 1.0 SDK (serverlessworkflow-types 7.25.0.Final).
 *
 * Usage:
 *   swf-migrate <input-file> [-o <output-file>] [-f yaml|json] [-n <namespace>] [--strict true|false] [--report-format json|markdown]
 *
 * Output defaults to <input-stem>-migrated.yaml if -o is not given.
 * Both JSON (.json) and YAML (.yaml / .yml) input files are supported.
 */
public class SpecConvert {

    private static final Logger log = LoggerFactory.getLogger(SpecConvert.class);

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || "-h".equals(args[0]) || "--help".equals(args[0])) {
            util.printUsage();
            return;
        }

        // Parse arguments: swf-migrate <input> [-o <output>]
        Path inputPath = null;
        Path outputPath = null;
        Path reportPath = null;
        String outFormat = "yaml";
        boolean outFormatExplicit = false;
        String namespace = "default";
        boolean strict = false;
        String reportFormat = "json";

        for (int i = 0; i < args.length; i++) {
            if ("-o".equals(args[i]) || "--output".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(args[i] + " requires a file path argument.");
                }
                outputPath = Path.of(args[++i]);
            } else if ("-f".equals(args[i]) || "--format".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("-f requires a format argument.");
                } else if (!(args[i+1].equals("yaml") || args[i+1].equals("json"))){
                    throw new IllegalArgumentException(args[i] + " requires either 'json' or 'yaml' as format.");
                }
                outFormat = (args[++i]);
                outFormatExplicit = true;
            } else if ("-n".equals(args[i]) || "--namespace".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(args[i] + " requires a namespace argument.");
                }
                namespace = (args[++i]);
            } else if ("-r".equals(args[i]) || "--report".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException(args[i] + " requires a file path argument.");
                }
                reportPath = Path.of(args[++i]);
            } else if ("--report-format".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--report-format requires 'json' or 'markdown' as an argument.");
                }
                String val = args[++i];
                 if ("json".equals(val)) {
                    reportFormat = val;
                } else if ("markdown".equals(val) || "md".equals(val)) {
                    reportFormat = "markdown";
                } else {
                    throw new IllegalArgumentException("--report-format requires 'json', 'md', or 'markdown', got: '" + val + "'.");
                }
            } else if ("--strict".equals(args[i])) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--strict requires 'true' or 'false' as an argument.");
                }
                String val = args[++i];
                if ("true".equals(val)) {
                    strict = true;
                } else if ("false".equals(val)) {
                    strict = false;
                } else {
                    throw new IllegalArgumentException("--strict requires 'true' or 'false', got: '" + val + "'.");
                }
            } else if (inputPath == null) {
                inputPath = Path.of(args[i]);
            } else {
                throw new IllegalArgumentException("Unexpected argument: " + args[i]);
            }
        }

        if (inputPath == null) {
            throw new IllegalArgumentException("No input file specified.");
        }

        // If -o was given without -f, infer the format from the output file extension.
        // If both were given explicitly and they conflict, throw.
        if (outputPath != null) {
            String outputFileName = outputPath.getFileName().toString().toLowerCase();
            boolean isJsonExt = outputFileName.endsWith(".json");
            boolean isYamlExt = outputFileName.endsWith(".yaml") || outputFileName.endsWith(".yml");

            if (outFormatExplicit) {
                // Both -o and -f supplied — they must agree.
                boolean matches = "json".equals(outFormat) ? isJsonExt : isYamlExt;
                if (!matches) {
                    throw new IllegalArgumentException(
                            "Output path '" + outputPath.getFileName() + "' does not match -f '" + outFormat + "'. "
                            + "Expected extension: " + ("yaml".equals(outFormat) ? ".yaml or .yml" : ".json") + ".");
                }
            } else if (isJsonExt) {
                // -o given alone with a .json extension — infer json format
                outFormat = "json";
            }
            // .yaml/.yml with no -f keeps the default "yaml"; any other extension also keeps "yaml"
        }

        // Validate that an explicit --report path extension matches --report-format
        if (reportPath != null) {
            String reportFileName = reportPath.getFileName().toString().toLowerCase();
            boolean extensionMatchesFormat;
            if ("markdown".equals(reportFormat)) {
                extensionMatchesFormat = reportFileName.endsWith(".md") || reportFileName.endsWith(".markdown");
            } else {
                extensionMatchesFormat = reportFileName.endsWith(".json");
            }
            if (!extensionMatchesFormat) {
                throw new IllegalArgumentException(
                        "Report path '" + reportPath.getFileName() + "' does not match --report-format '" + reportFormat + "'. "
                        + "Expected extension: " + ("markdown".equals(reportFormat) ? ".md or .markdown" : ".json") + ".");
            }
        }

        // Default output: <stem>-migrated.yaml alongside the input file
        if (outputPath == null) {
            String inputName = inputPath.getFileName().toString();
            String stem = inputName.contains(".")
                    ? inputName.substring(0, inputName.lastIndexOf('.'))
                    : inputName;
            Path parent = inputPath.getParent();
            outputPath = (parent != null ? parent : Path.of(".")).resolve(stem + "-migrated." + outFormat);
        }

        // Initialise report collector for this run
        ReportCollector.init(inputPath.getFileName().toString());

        io.serverlessworkflow.api.Workflow wf08 = read(inputPath);
        int totalStates = wf08.getStates() != null ? wf08.getStates().size() : 0;

        io.serverlessworkflow.api.types.Workflow wf10 = convert(wf08, namespace);

        WorkflowFormat format = WorkflowFormat.fromPath(outputPath);

        // Suppress zero-valued duration fields (days:0, hours:0, etc.) from the output
        format.mapper().addMixIn(DurationInline.class, DurationInlineMixIn.class);

        WorkflowWriter.writeWorkflow(outputPath, wf10, format);
        log.info("Wrote converted file to: {}", outputPath);

        // ----------------------------------------------------------------
        // Validate the serialised 1.0 output
        // ----------------------------------------------------------------
        ObjectMapper validationMapper = util.isYaml(outputPath)
                ? new com.fasterxml.jackson.dataformat.yaml.YAMLMapper()
                : new ObjectMapper();
        JsonNode outputTree = validationMapper.readTree(outputPath.toFile());
        List<ValidationResult> validationResults = new OutputValidator().validate(outputTree);
        for (ValidationResult vr : validationResults) {
            System.err.println("[" + vr.severity + "] validation: " + vr.path + " — " + vr.rule + ": " + vr.message);
            MigrationReport.Severity severity = vr.severity == ValidationResult.Severity.ERROR
                    ? MigrationReport.Severity.ERROR
                    : MigrationReport.Severity.WARNING;
            ReportCollector.get().addIssue(
                    severity,
                    MigrationReport.Category.validation,
                    vr.path,
                    vr.message,
                    null, null,
                    vr.rule);
        }
        if (validationResults.isEmpty()) {
            System.err.println("[INFO] Output validation passed with no findings.");
        }

        // Finalise and write the migration report
        int migratedStates = wf10.getDo() != null ? wf10.getDo().size() : 0;
        boolean failed = ReportCollector.get().finalise(totalStates, migratedStates, strict);
        MigrationReport report = ReportCollector.get().getReport();

        String inputName = inputPath.getFileName().toString();
        String stem = inputName.contains(".")
                ? inputName.substring(0, inputName.lastIndexOf('.'))
                : inputName;
        if (reportPath == null) {
            String reportExtension = "markdown".equals(reportFormat) ? "md" : "json";
            reportPath = (outputPath.getParent() != null
                    ? outputPath.getParent() : Path.of(".")).resolve(stem + "-report." + reportExtension);
        }
        ReportWriter.forFormat(reportFormat).write(report, reportPath);
        log.info("Wrote migration report to: {}", reportPath);

        if (failed) {
            log.error("Strict mode is enabled and warnings were produced — exiting with failure.");
            System.exit(1);
        }
    }

    /**
     * Parse a JSON or YAML file into a 0.8 workflow instance.
     */
    public static io.serverlessworkflow.api.Workflow read(Path path) throws IOException {
        ObjectMapper mapper = util.isYaml(path) ? new YamlObjectMapper() : new JsonObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper.readValue(path.toFile(), io.serverlessworkflow.api.Workflow.class);
    }

    /**
     * Convert a parsed 0.8 workflow into 1.0.
     */
    public static io.serverlessworkflow.api.types.Workflow convert(
            io.serverlessworkflow.api.Workflow src, String namespace) {

        Document document = buildDocument(src, namespace);
        List<TaskItem> doList = buildDo(src);
        return new io.serverlessworkflow.api.types.Workflow(document, doList);
    }

    // ---------------------------------------------------------------
    // 1.0 document builder
    // ---------------------------------------------------------------

    /**
     * Build the top-level document block from 0.8 fields
     */
    private static Document buildDocument(io.serverlessworkflow.api.Workflow src, String namespace) {
        // dsl — always "1.0.0" for output
        String dsl = "1.0.0";
        System.err.println("[INFO] dsl set to " + dsl);

        // namespace — 0.8 spec has no namespace field; fall back to "default"
        System.err.println("[INFO] namespace set to " + namespace);

        // name — mapped from 0.8 "id"
        String name = src.getId() != null ? src.getId() : "unnamed";
        System.err.println("[INFO] name set to " + name);

        // version — carried over as-is
        String version = src.getVersion() != null ? src.getVersion() : "0.0.1";
        System.err.println("[INFO] version set to " + version);

        return new Document(dsl, namespace, name, version);
    }

    // -----------------------------------------------------------------------
    // 1.0 do-list builder
    // -----------------------------------------------------------------------

    /**
     * Build the 1.0 do block from the 0.8 states list.
     * Each state becomes a TaskItem keyed by the state's name.
     *
     * Handled mappings:
     *   inject    - set             (state data → set variables)
     *   sleep     - wait            (ISO 8601 duration → DurationInline)
     *   switch    - switch          (dataConditions + defaultCondition)
     *   parallel  - fork            (branches + completionType)
     *   event     - listen          (onEvents + exclusive flag)
     *   operation - call            (actions → call tasks; sequential = do, parallel = fork)
     *   forEach   - for             (inputCollection + iterationParam + actions)
     *   callback  - do[call+listen+switch]  (action → listen → conditional route)
     */
    private static List<TaskItem> buildDo(io.serverlessworkflow.api.Workflow src) {
        List<TaskItem> items = new ArrayList<>();

        if (src.getStates() == null) {
            return items;
        }

        // Build a name→type lookup from the workflow's top-level event definitions
        Map<String, String> eventTypeByName = util.buildEventTypeMap(src);

        for (State state : src.getStates()) {
            String stateName = state.getName() != null ? state.getName() : "unnamed";

            if (state instanceof InjectState) {
                items.add(Inject.handleInject(stateName, (InjectState) state));

            } else if (state instanceof SleepState) {
                items.add(Sleep.handleSleep(stateName, (SleepState) state));

            } else if (state instanceof SwitchState) {
                items.add(Switch.handleSwitch(stateName, (SwitchState) state, eventTypeByName));

            } else if (state instanceof ParallelState) {
                items.add(Parallel.handleParallel(stateName, (ParallelState) state));

            } else if (state instanceof OperationState) {
                items.add(Operation.handleOperation(stateName, (OperationState) state));

            } else if (state instanceof EventState) {
                items.add(Event.handleEvent(stateName, (EventState) state, eventTypeByName));

            } else if (state instanceof ForEachState) {
                items.add(ForEach.handleForEach(stateName, (ForEachState) state));

            } else if (state instanceof CallbackState) {
                items.add(Callback.handleCallback(stateName, (CallbackState) state, eventTypeByName));

            } else {
                System.err.println("[WARN] Unsupported state type for state '"
                        + stateName + "' (" + state.getClass().getSimpleName() + "); skipping.");
                ReportCollector.get().addIssue(
                        org.openworkflow.migrationtool.report.MigrationReport.Severity.ERROR,
                        org.openworkflow.migrationtool.report.MigrationReport.Category.unsupported_feature,
                        "states[" + stateName + "]",
                        "State type " + state.getClass().getSimpleName() + " has no 1.0 equivalent; state was skipped.",
                        null, null, "Manually implement this state in the converted workflow.");
            }
        }

        return items;
    }
  
}
