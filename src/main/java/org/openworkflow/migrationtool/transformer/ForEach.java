package org.openworkflow.migrationtool.transformer;

import org.openworkflow.migrationtool.report.MigrationReport.Category;
import org.openworkflow.migrationtool.report.MigrationReport.Severity;
import org.openworkflow.migrationtool.report.ReportCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 0.8
import io.serverlessworkflow.api.actions.Action;
import io.serverlessworkflow.api.states.ForEachState;

// 1.0
import io.serverlessworkflow.api.types.FlowDirective;
import io.serverlessworkflow.api.types.ForTask;
import io.serverlessworkflow.api.types.ForTaskConfiguration;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskItem;

public class ForEach {
    private static final Logger log = LoggerFactory.getLogger(ForEach.class);
    /**
     * Convert a 0.8 forEach state to a 1.0 for task.
     *
     * Field mapping:
     *   inputCollection  → for.in   (the collection expression to iterate over)
     *   iterationParam   → for.each (the variable name bound to each item; defaults to "item")
     *   actions          → do       (converted to call tasks via util.convertAction)
     *
     * outputCollection and batchSize have no direct 1.0 equivalents and are logged as warnings.
     */
    public static TaskItem handleForEach(String name, ForEachState state) {
        return handleForEachFunction(name, state);
    }

    protected static TaskItem handleForEachFunction(String name, ForEachState state) {
        String in = state.getInputCollection() != null ? state.getInputCollection() : "${ .[] }";
        String each = state.getIterationParam() != null ? state.getIterationParam() : "item";

        log.info("Converting forEach state '{}' (in={}, each={})", name, in, each);

        if (state.getOutputCollection() != null) {
            log.warn("forEach state '{}': outputCollection has no 1.0 equivalent; value '{}' will be dropped.",
                    name, state.getOutputCollection());
            ReportCollector.get().addIssue(Severity.WARNING, Category.unsupported_feature,
                    "states[" + name + "].outputCollection",
                    "outputCollection has no 1.0 equivalent and will be dropped.",
                    state.getOutputCollection(), null,
                    "Manually implement output collection logic if required.");
        }
        if (state.getBatchSize() > 0) {
            log.warn("forEach state '{}': batchSize has no 1.0 equivalent; value {} will be dropped.",
                    name, state.getBatchSize());
            ReportCollector.get().addIssue(Severity.WARNING, Category.unsupported_feature,
                    "states[" + name + "].batchSize",
                    "batchSize has no 1.0 equivalent and will be dropped.",
                    String.valueOf(state.getBatchSize()), null,
                    "Manually implement batching logic if required.");
        }

        List<Action> actions = state.getActions() != null
                ? state.getActions() : Collections.emptyList();

        List<TaskItem> doItems = new ArrayList<>();
        for (Action action : actions) {
            doItems.add(util.convertAction(action));
        }

        ForTaskConfiguration forCfg = new ForTaskConfiguration()
                .withEach(each)
                .withIn(in);

        ForTask forTask = new ForTask()
                .withFor(forCfg)
                .withDo(doItems);

        FlowDirective then = util.resolveThen(name, state);
        if (then != null) {
            forTask.withThen(then);
        }

        return new TaskItem(name, new Task().withForTask(forTask));
    }
}
