package com.specconvert.validator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates a converted 1.0 Serverless Workflow document against the constraints that are
 * relevant to the 0.8→1.0 migration produced by {@code swf-migrate}.
 *
 * <h2>Scope</h2>
 * <p>Only elements that are the direct product of the translation are checked here.
 * Pure 1.0-only constructs with no 0.8 antecedent (e.g. {@code retry}, {@code timeout},
 * {@code auth}, {@code use}) are intentionally outside scope.
 *
 * <h2>Checked constraints</h2>
 * <pre>
 *  document block
 *    – dsl present, equals "1.0.0"
 *    – namespace present and non-blank
 *    – name present and non-blank
 *    – version present and non-blank
 *
 *  do block
 *    – present and is a non-empty array
 *    – each element is a single-key object (task item)
 *    – task key is a non-blank string
 *
 *  set task  (← inject state)
 *    – set field present and is an object
 *
 *  wait task  (← sleep state)
 *    – wait field present and is an object
 *    – at least one of: seconds, minutes, hours, days is a positive integer
 *
 *  switch task  (← switch state / callback route)
 *    – switch field present and is a non-empty array
 *    – exactly one "default" case per switch array
 *    – every non-default case has a "when" field
 *    – every case has a "then" field
 *    – no "_warning" marker fields (signals unresolved EL expressions)
 *    – no "then" value equals the literal "TODO"
 *
 *  call task  (← operation state action / callback action)
 *    – call field present and is a non-blank string
 *    – with field, when present, is an object
 *
 *  for task  (← forEach state)
 *    – for.in present and non-blank
 *    – for.each present and non-blank
 *    – do present and is a non-empty array
 *
 *  fork task  (← parallel state / parallel operation)
 *    – fork.branches present and is a non-empty array
 *    – fork.compete present and is a boolean
 *
 *  listen task  (← event state / callback listen step)
 *    – listen.to present and is an object
 *    – listen.to has exactly one of "any" or "all"
 *    – the chosen array has at least one event filter
 *    – each event filter has a "with.type" field
 *
 *  do task  (← callback composite / sequential operation)
 *    – do field present and is a non-empty array
 *    – recursively validated
 * </pre>
 */
public class OutputValidator {

    // Known 1.0 task types that are produced by the translator
    private static final List<String> TASK_TYPES =
            List.of("set", "wait", "switch", "call", "for", "fork", "listen", "do");

    /**
     * Validate a converted 1.0 workflow document tree.
     *
     * @param root the root {@link JsonNode} of the serialised 1.0 workflow
     * @return list of findings; empty means the output is structurally valid
     */
    public List<ValidationResult> validate(JsonNode root) {
        List<ValidationResult> results = new ArrayList<>();

        validateDocument(root, results);
        validateDoBlock(root, "do", results);

        return results;
    }

    // ------------------------------------------------------------------
    // document block
    // ------------------------------------------------------------------

    private void validateDocument(JsonNode root, List<ValidationResult> results) {
        JsonNode doc = root.get("document");
        if (doc == null || doc.isMissingNode()) {
            error(results, "document", "missing_field", "'document' block is absent from the converted workflow.");
            return;
        }

        requireNonBlankString(doc, "document", "dsl", results);
        requireNonBlankString(doc, "document", "name", results);
        requireNonBlankString(doc, "document", "namespace", results);
        // version may be serialised as a bare number (e.g. 1.0 in YAML) — accept text or number
        requireNonBlankStringOrNumber(doc, "document", "version", results);

        // dsl must be exactly "1.0.0" — any other value indicates a mis-conversion
        JsonNode dsl = doc.get("dsl");
        if (dsl != null && dsl.isTextual() && !"1.0.0".equals(dsl.asText())) {
            error(results, "document.dsl", "invalid_value",
                    "Expected 'dsl' to be '1.0.0' but found '" + dsl.asText() + "'.");
        }
    }

    // ------------------------------------------------------------------
    // do block (top-level and recursive)
    // ------------------------------------------------------------------

    private void validateDoBlock(JsonNode parent, String fieldPath, List<ValidationResult> results) {
        JsonNode doNode = parent.get("do");
        if (doNode == null || doNode.isMissingNode()) {
            error(results, fieldPath, "missing_field",
                    "'" + fieldPath + "' block is absent; the workflow has no tasks.");
            return;
        }
        if (!doNode.isArray()) {
            error(results, fieldPath, "wrong_type",
                    "'" + fieldPath + "' must be an array of task items.");
            return;
        }
        if (doNode.size() == 0) {
            warn(results, fieldPath, "empty_list",
                    "'" + fieldPath + "' is empty; no tasks were translated.");
            return;
        }

        for (int i = 0; i < doNode.size(); i++) {
            validateTaskItem(doNode.get(i), fieldPath + "[" + i + "]", results);
        }
    }

    // ------------------------------------------------------------------
    // Task item — single-key object { "<name>": { <task body> } }
    // ------------------------------------------------------------------

    private void validateTaskItem(JsonNode item, String path, List<ValidationResult> results) {
        if (!item.isObject()) {
            error(results, path, "wrong_type", "Task item must be an object.");
            return;
        }
        if (item.size() != 1) {
            error(results, path, "invalid_structure",
                    "Task item must contain exactly one key (the task name), but found " + item.size() + " keys.");
            return;
        }

        String taskName = item.fieldNames().next();
        if (taskName == null || taskName.isBlank()) {
            error(results, path, "blank_task_name", "Task item key (name) must be a non-blank string.");
            return;
        }

        String taskPath = path + "." + taskName;
        JsonNode body   = item.get(taskName);

        if (body == null || body.isMissingNode() || !body.isObject()) {
            error(results, taskPath, "missing_task_body",
                    "Task '" + taskName + "' has no body object.");
            return;
        }

        validateTaskBody(body, taskPath, results);
    }

    // ------------------------------------------------------------------
    // Task body — dispatch on the task-type key present in the body
    // ------------------------------------------------------------------

    private void validateTaskBody(JsonNode body, String path, List<ValidationResult> results) {
        // Determine which task type key is present
        String foundType = null;
        for (String type : TASK_TYPES) {
            if (body.has(type)) {
                foundType = type;
                break;
            }
        }

        if (foundType == null) {
            error(results, path, "unknown_task_type",
                    "Task body does not contain any recognised task-type field. "
                    + "Expected one of: " + String.join(", ", TASK_TYPES) + ".");
            return;
        }

        switch (foundType) {
            case "set"    -> validateSetTask(body, path, results);
            case "wait"   -> validateWaitTask(body, path, results);
            case "switch" -> validateSwitchTask(body, path, results);
            case "call"   -> validateCallTask(body, path, results);
            case "for"    -> validateForTask(body, path, results);
            case "fork"   -> validateForkTask(body, path, results);
            case "listen" -> validateListenTask(body, path, results);
            case "do"     -> validateDoTask(body, path, results);
        }
    }

    // ------------------------------------------------------------------
    // set task  (← inject state)
    // ------------------------------------------------------------------

    private void validateSetTask(JsonNode body, String path, List<ValidationResult> results) {
        JsonNode set = body.get("set");
        if (set == null || set.isMissingNode()) {
            error(results, path + ".set", "missing_field", "'set' field is absent from set task.");
            return;
        }
        if (!set.isObject()) {
            error(results, path + ".set", "wrong_type",
                    "'set' must be an object containing the variables to assign.");
        }
    }

    // ------------------------------------------------------------------
    // wait task  (← sleep state)
    // ------------------------------------------------------------------

    private void validateWaitTask(JsonNode body, String path, List<ValidationResult> results) {
        JsonNode wait = body.get("wait");
        if (wait == null || wait.isMissingNode()) {
            error(results, path + ".wait", "missing_field", "'wait' field is absent from wait task.");
            return;
        }
        if (!wait.isObject()) {
            error(results, path + ".wait", "wrong_type",
                    "'wait' must be an object containing duration fields.");
            return;
        }

        // At least one positive duration component must be present
        boolean hasPositiveDuration = false;
        for (String field : List.of("seconds", "minutes", "hours", "days")) {
            JsonNode v = wait.get(field);
            if (v != null && v.isInt() && v.asInt() > 0) {
                hasPositiveDuration = true;
                break;
            }
        }
        if (!hasPositiveDuration) {
            warn(results, path + ".wait", "zero_duration",
                    "wait task has no positive duration component (seconds/minutes/hours/days). "
                    + "This is a zero-duration wait — verify the original ISO 8601 duration was parsed correctly.");
        }
    }

    // ------------------------------------------------------------------
    // switch task  (← switch state / callback route)
    // ------------------------------------------------------------------

    private void validateSwitchTask(JsonNode body, String path, List<ValidationResult> results) {
        JsonNode sw = body.get("switch");
        if (sw == null || sw.isMissingNode()) {
            error(results, path + ".switch", "missing_field", "'switch' field is absent from switch task.");
            return;
        }
        if (!sw.isArray()) {
            error(results, path + ".switch", "wrong_type", "'switch' must be an array of case items.");
            return;
        }
        if (sw.size() == 0) {
            error(results, path + ".switch", "empty_list",
                    "'switch' array is empty; at least one case (default) is required.");
            return;
        }

        int defaultCount = 0;
        for (int i = 0; i < sw.size(); i++) {
            JsonNode caseItem = sw.get(i);
            String casePath = path + ".switch[" + i + "]";
            validateSwitchCaseItem(caseItem, casePath, results);

            // Count default cases
            if (caseItem.isObject() && caseItem.has("default")) {
                defaultCount++;
            }
        }

        if (defaultCount == 0) {
            warn(results, path + ".switch", "no_default_case",
                    "switch task has no 'default' case. Workflows without a default may get stuck.");
        } else if (defaultCount > 1) {
            error(results, path + ".switch", "duplicate_default_case",
                    "switch task has " + defaultCount + " 'default' cases; exactly one is allowed.");
        }
    }

    private void validateSwitchCaseItem(JsonNode caseItem, String path, List<ValidationResult> results) {
        if (!caseItem.isObject() || caseItem.size() != 1) {
            error(results, path, "invalid_structure",
                    "Switch case item must be a single-key object keyed by the case name.");
            return;
        }

        String caseName  = caseItem.fieldNames().next();
        JsonNode caseBody = caseItem.get(caseName);
        String casePath  = path + "." + caseName;

        if (caseBody == null || !caseBody.isObject()) {
            error(results, casePath, "missing_case_body",
                    "Switch case '" + caseName + "' has no body object.");
            return;
        }

        // Non-default cases must have a "when" predicate
        if (!"default".equals(caseName)) {
            if (!caseBody.has("when") || caseBody.get("when").asText("").isBlank()) {
                error(results, casePath + ".when", "missing_field",
                        "Non-default switch case '" + caseName + "' is missing the 'when' predicate.");
            } else {
                // Check for unresolved EL expressions left as TODO
                String when = caseBody.get("when").asText("");
                if ("TODO".equals(when)) {
                    warn(results, casePath + ".when", "todo_placeholder",
                            "Switch case '" + caseName + "' has a 'TODO' placeholder in 'when'. "
                            + "Manually replace with a valid jq expression.");
                }
            }
        }

        // Every case must have a "then" directive
        if (!caseBody.has("then")) {
            error(results, casePath + ".then", "missing_field",
                    "Switch case '" + caseName + "' is missing the 'then' flow directive.");
        } else {
            String then = caseBody.get("then").asText("");
            if ("TODO".equals(then)) {
                error(results, casePath + ".then", "todo_placeholder",
                        "Switch case '" + caseName + "' has a 'TODO' placeholder in 'then'. "
                        + "Set the correct next state or 'end'.");
            }
        }

        // Warn on any _warning marker field injected by the EL-expression handler
        if (caseBody.has("_warning")) {
            warn(results, casePath + "._warning", "unresolved_expression",
                    "Switch case '" + caseName + "' contains a '_warning' marker: "
                    + caseBody.get("_warning").asText()
                    + " — the 'when' expression requires manual translation to jq syntax.");
        }
    }

    // ------------------------------------------------------------------
    // call task  (← operation action / callback action)
    // ------------------------------------------------------------------

    private void validateCallTask(JsonNode body, String path, List<ValidationResult> results) {
        JsonNode call = body.get("call");
        if (call == null || call.isMissingNode()) {
            error(results, path + ".call", "missing_field", "'call' field is absent from call task.");
            return;
        }
        if (!call.isTextual() || call.asText().isBlank()) {
            error(results, path + ".call", "invalid_value",
                    "'call' must be a non-blank string naming the function to invoke.");
        }

        JsonNode with = body.get("with");
        if (with != null && !with.isMissingNode() && !with.isObject()) {
            error(results, path + ".with", "wrong_type",
                    "'with' must be an object containing the function arguments.");
        }
    }

    // ------------------------------------------------------------------
    // for task  (← forEach state)
    // ------------------------------------------------------------------

    private void validateForTask(JsonNode body, String path, List<ValidationResult> results) {
        JsonNode forNode = body.get("for");
        if (forNode == null || forNode.isMissingNode()) {
            error(results, path + ".for", "missing_field", "'for' field is absent from for task.");
            return;
        }
        if (!forNode.isObject()) {
            error(results, path + ".for", "wrong_type", "'for' must be an object.");
            return;
        }

        // for.in — collection expression
        JsonNode in = forNode.get("in");
        if (in == null || in.isMissingNode() || !in.isTextual() || in.asText().isBlank()) {
            error(results, path + ".for.in", "missing_field",
                    "'for.in' must be a non-blank expression identifying the collection to iterate.");
        }

        // for.each — iteration variable name
        JsonNode each = forNode.get("each");
        if (each == null || each.isMissingNode() || !each.isTextual() || each.asText().isBlank()) {
            error(results, path + ".for.each", "missing_field",
                    "'for.each' must be a non-blank variable name bound to each item.");
        }

        // do — the body to execute for each item
        if (!body.has("do")) {
            error(results, path + ".do", "missing_field",
                    "for task is missing a 'do' block listing the tasks to run per iteration.");
        } else {
            JsonNode doNode = body.get("do");
            if (!doNode.isArray() || doNode.size() == 0) {
                warn(results, path + ".do", "empty_list",
                        "for task 'do' is empty; no per-iteration tasks will execute.");
            } else {
                for (int i = 0; i < doNode.size(); i++) {
                    validateTaskItem(doNode.get(i), path + ".do[" + i + "]", results);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // fork task  (← parallel state / parallel operation)
    // ------------------------------------------------------------------

    private void validateForkTask(JsonNode body, String path, List<ValidationResult> results) {
        JsonNode fork = body.get("fork");
        if (fork == null || fork.isMissingNode()) {
            error(results, path + ".fork", "missing_field", "'fork' field is absent from fork task.");
            return;
        }
        if (!fork.isObject()) {
            error(results, path + ".fork", "wrong_type", "'fork' must be an object.");
            return;
        }

        // compete — boolean
        JsonNode compete = fork.get("compete");
        if (compete == null || compete.isMissingNode()) {
            error(results, path + ".fork.compete", "missing_field",
                    "'fork.compete' is absent. Set to false (all branches) or true (first to complete wins).");
        } else if (!compete.isBoolean()) {
            error(results, path + ".fork.compete", "wrong_type",
                    "'fork.compete' must be a boolean.");
        }

        // branches — non-empty array
        JsonNode branches = fork.get("branches");
        if (branches == null || branches.isMissingNode()) {
            error(results, path + ".fork.branches", "missing_field",
                    "'fork.branches' is absent; at least one branch is required.");
        } else if (!branches.isArray()) {
            error(results, path + ".fork.branches", "wrong_type",
                    "'fork.branches' must be an array.");
        } else if (branches.size() == 0) {
            error(results, path + ".fork.branches", "empty_list",
                    "'fork.branches' is empty; at least one branch is required.");
        } else {
            for (int i = 0; i < branches.size(); i++) {
                validateTaskItem(branches.get(i), path + ".fork.branches[" + i + "]", results);
            }
        }
    }

    // ------------------------------------------------------------------
    // listen task  (← event state / callback listen step)
    // ------------------------------------------------------------------

    private void validateListenTask(JsonNode body, String path, List<ValidationResult> results) {
        JsonNode listen = body.get("listen");
        if (listen == null || listen.isMissingNode()) {
            error(results, path + ".listen", "missing_field", "'listen' field is absent from listen task.");
            return;
        }
        if (!listen.isObject()) {
            error(results, path + ".listen", "wrong_type", "'listen' must be an object.");
            return;
        }

        JsonNode to = listen.get("to");
        if (to == null || to.isMissingNode()) {
            error(results, path + ".listen.to", "missing_field",
                    "'listen.to' is absent; it must specify 'any' or 'all' event filters.");
            return;
        }
        if (!to.isObject()) {
            error(results, path + ".listen.to", "wrong_type", "'listen.to' must be an object.");
            return;
        }

        boolean hasAny = to.has("any");
        boolean hasAll = to.has("all");

        if (!hasAny && !hasAll) {
            error(results, path + ".listen.to", "missing_field",
                    "'listen.to' must contain either 'any' or 'all' to specify which events to listen for.");
            return;
        }
        if (hasAny && hasAll) {
            error(results, path + ".listen.to", "conflicting_fields",
                    "'listen.to' must not contain both 'any' and 'all'.");
            return;
        }

        String strategyKey = hasAny ? "any" : "all";
        JsonNode filters = to.get(strategyKey);
        if (!filters.isArray() || filters.size() == 0) {
            error(results, path + ".listen.to." + strategyKey, "empty_list",
                    "'listen.to." + strategyKey + "' must be a non-empty array of event filters.");
        } else {
            for (int i = 0; i < filters.size(); i++) {
                validateEventFilter(filters.get(i), path + ".listen.to." + strategyKey + "[" + i + "]", results);
            }
        }
    }

    private void validateEventFilter(JsonNode filter, String path, List<ValidationResult> results) {
        if (!filter.isObject()) {
            error(results, path, "wrong_type", "Event filter must be an object.");
            return;
        }

        JsonNode with = filter.get("with");
        if (with == null || with.isMissingNode()) {
            warn(results, path + ".with", "missing_field",
                    "Event filter has no 'with' object. Typically at minimum 'with.type' should be set.");
            return;
        }
        if (!with.isObject()) {
            error(results, path + ".with", "wrong_type", "Event filter 'with' must be an object.");
            return;
        }

        JsonNode type = with.get("type");
        if (type == null || type.isMissingNode() || !type.isTextual() || type.asText().isBlank()) {
            warn(results, path + ".with.type", "missing_field",
                    "Event filter is missing 'with.type'. A CloudEvent type filter is strongly recommended "
                    + "to avoid consuming unintended events.");
        }
    }

    // ------------------------------------------------------------------
    // do task  (← callback composite / sequential operation)
    // ------------------------------------------------------------------

    private void validateDoTask(JsonNode body, String path, List<ValidationResult> results) {
        JsonNode doNode = body.get("do");
        if (doNode == null || doNode.isMissingNode()) {
            error(results, path + ".do", "missing_field", "'do' field is absent from do task.");
            return;
        }
        if (!doNode.isArray()) {
            error(results, path + ".do", "wrong_type", "'do' must be an array of task items.");
            return;
        }
        if (doNode.size() == 0) {
            warn(results, path + ".do", "empty_list",
                    "'do' task is empty; no steps will execute.");
            return;
        }

        for (int i = 0; i < doNode.size(); i++) {
            validateTaskItem(doNode.get(i), path + ".do[" + i + "]", results);
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void requireNonBlankString(JsonNode parent, String parentPath,
                                       String field, List<ValidationResult> results) {
        JsonNode node = parent.get(field);
        if (node == null || node.isMissingNode()) {
            error(results, parentPath + "." + field, "missing_field",
                    "'" + parentPath + "." + field + "' is absent.");
        } else if (!node.isTextual() || node.asText().isBlank()) {
            error(results, parentPath + "." + field, "invalid_value",
                    "'" + parentPath + "." + field + "' must be a non-blank string.");
        }
    }

    /**
     * Like {@link #requireNonBlankString} but also accepts numeric nodes (e.g. YAML bare floats
     * such as {@code 1.0} for version fields where the SDK omits quotes).
     */
    private void requireNonBlankStringOrNumber(JsonNode parent, String parentPath,
                                               String field, List<ValidationResult> results) {
        JsonNode node = parent.get(field);
        if (node == null || node.isMissingNode()) {
            error(results, parentPath + "." + field, "missing_field",
                    "'" + parentPath + "." + field + "' is absent.");
        } else if (!node.isTextual() && !node.isNumber()) {
            error(results, parentPath + "." + field, "invalid_value",
                    "'" + parentPath + "." + field + "' must be a non-blank string or number.");
        } else if (node.isTextual() && node.asText().isBlank()) {
            error(results, parentPath + "." + field, "invalid_value",
                    "'" + parentPath + "." + field + "' must not be blank.");
        }
    }

    private static void error(List<ValidationResult> results, String path, String rule, String message) {
        results.add(new ValidationResult(ValidationResult.Severity.ERROR, path, rule, message));
    }

    private static void warn(List<ValidationResult> results, String path, String rule, String message) {
        results.add(new ValidationResult(ValidationResult.Severity.WARNING, path, rule, message));
    }
}
