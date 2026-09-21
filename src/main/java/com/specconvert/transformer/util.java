package com.specconvert.transformer;

import com.fasterxml.jackson.databind.JsonNode;
import com.specconvert.report.MigrationReport.Category;
import com.specconvert.report.MigrationReport.Severity;
import com.specconvert.report.ReportCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.HashMap;

// 0.8
import io.serverlessworkflow.api.actions.Action;
import io.serverlessworkflow.api.events.EventDefinition;
import io.serverlessworkflow.api.transitions.Transition;

// 1.0
import io.serverlessworkflow.api.types.CallFunction;
import io.serverlessworkflow.api.types.CallTask;
import io.serverlessworkflow.api.types.FunctionArguments;
import io.serverlessworkflow.api.types.Set;
import io.serverlessworkflow.api.types.SetTask;
import io.serverlessworkflow.api.types.SetTaskConfiguration;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskItem;
import java.util.Map;

public class util {

    private static final Logger log = LoggerFactory.getLogger(util.class);
    /**
     * Convert a single 0.8 action to a 1.0 TaskItem.
     *
     * A functionRef action becomes a CallFunction task keyed by the function's refName.
     * Arguments (JsonNode object) are copied as additional properties on FunctionArguments.
     */
    protected static TaskItem convertAction(Action action) {
        if (action.getFunctionRef() != null) {
            String refName = action.getFunctionRef().getRefName() != null
                    ? action.getFunctionRef().getRefName() : "function";

            FunctionArguments args = new FunctionArguments();
            JsonNode arguments = action.getFunctionRef().getArguments();
            if (arguments != null && arguments.isObject()) {
                arguments.fields().forEachRemaining(e -> args.setAdditionalProperty(e.getKey(), e.getValue()));
            }

            CallFunction callFn = new CallFunction()
                    .withCall(refName)
                    .withWith(args);

            String taskName = action.getName() != null ? action.getName() : refName;
            return new TaskItem(taskName, new Task().withCallTask(new CallTask().withCallFunction(callFn)));
        }

        // Fallback: unsupported action type — emit a set task with a warning marker
        String actionName = action.getName() != null ? action.getName() : "unknown";
        System.err.println("[WARN] Action has no functionRef; emitting placeholder set task.");
        ReportCollector.get().addIssue(Severity.WARNING, Category.unsupported_feature,
                "action(" + actionName + ")",
                "Action has no functionRef; a placeholder set task was emitted.",
                null, null, "Replace the placeholder set task with the correct 1.0 call task.");
        SetTaskConfiguration cfg = new SetTaskConfiguration();
        cfg.setAdditionalProperty("_warning", "unsupported action type");
        SetTask setTask = new SetTask().withSet(new Set().withSetTaskConfiguration(cfg));
        String taskName = action.getName() != null ? action.getName() : "unsupportedAction";
        return new TaskItem(taskName, new Task().withSetTask(setTask));
    }

    /**
     * Extract the next-state name from a 0.8 transition
     */
    protected static String transitionName(Transition t) {
        if (t == null || t.getNextState() == null) return "TODO";
        return t.getNextState();
    }

    /**
     * Convert a human-readable condition name like "Applicant is adult"
     * into a valid camelCase identifier like "applicantIsAdult" for use as a YAML key.
     */
    protected static String toIdentifier(String name) {
        String[] words = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i].replaceAll("[^a-zA-Z0-9]", "");
            if (word.isEmpty()) continue;
            if (i == 0) {
                sb.append(word.toLowerCase());
            } else {
                sb.append(Character.toUpperCase(word.charAt(0)));
                sb.append(word.substring(1).toLowerCase());
            }
        }
        return sb.isEmpty() ? "case" : sb.toString();
    }

    /**
     * Strip the 0.8 EL wrapper (${ ... }) from a condition string.
     * If the expression is not wrapped, it is returned as-is.
     * The inner content cannot be truly converted and will likely still need manual jq translation.
     */
    protected static String stripExpressionWrapper(String expression) {
        String trimmed = expression.trim();
        if (trimmed.startsWith("${") && trimmed.endsWith("}")) {
            String inner = trimmed.substring(2, trimmed.length() - 1).trim();
            System.err.println("[WARN] EL expression '" + inner + "' may need manual translation to jq syntax.");
            ReportCollector.get().addIssue(Severity.WARNING, Category.expression_conversion,
                    "expression",
                    "EL expression may need manual translation to jq syntax.",
                    trimmed, inner,
                    "Verify the jq expression produces the expected output.");
            return inner;
        }
        return trimmed;
    }

    public static void printUsage() {
        log.info("Usage: swf-migrate <input-file> [-o <output-file>] [-f yaml|json] [-n <namespace>] [--strict true|false] [--report-format json|markdown]");
        log.info("Convert a CNCF Serverless Workflow spec 0.8 document to 1.0.");
        log.info("");
        log.info("  -o, --output           Output file path (default: <input-stem>-migrated.yaml)");
        log.info("  -f, --format           Output format: yaml or json (default: yaml)");
        log.info("  -n, --namespace        Namespace in the 1.0 document header (default: default)");
        log.info("  -r, --report           Report file path (default: <input-stem>-report.json|md)");
        log.info("      --report-format    Report format: json or markdown (default: json)");
        log.info("      --strict           Treat warnings as failures; exit with code 1 if any warnings occur (default: false)");
    }

    /**
     * Returns true when the path has a .yaml or yml etension extension.
     */
    public static boolean isYaml(Path path) {
        if (path == null) return false;
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml");
    }

    /**
     * Build a map of event-definition name → CloudEvent type string
     * from the workflow's top-level events block.
     * Used by handleListen() to resolve eventRef names to CloudEvent types.
     */
    public static Map<String, String> buildEventTypeMap(io.serverlessworkflow.api.Workflow src) {
        Map<String, String> map = new HashMap<>();
        if (src.getEvents() == null || src.getEvents().getEventDefs() == null) {
            return map;
        }
        for (EventDefinition def : src.getEvents().getEventDefs()) {
            if (def.getName() != null) {
                // Use the declared CloudEvent type if present, otherwise fall back to the name
                String type = def.getType() != null ? def.getType() : def.getName();
                map.put(def.getName(), type);
            }
        }
        return map;
    }

}
