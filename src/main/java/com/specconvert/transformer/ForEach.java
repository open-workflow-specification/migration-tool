package com.specconvert.transformer;

import com.specconvert.report.MigrationReport.Category;
import com.specconvert.report.MigrationReport.Severity;
import com.specconvert.report.ReportCollector;
import java.util.ArrayList;
import java.util.List;

// 0.8
import io.serverlessworkflow.api.actions.Action;
import io.serverlessworkflow.api.states.ForEachState;

// 1.0
import io.serverlessworkflow.api.types.ForTask;
import io.serverlessworkflow.api.types.ForTaskConfiguration;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskItem;

public class ForEach {
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

        System.err.println("[INFO] Converting forEach state '" + name
                + "' (in=" + in + ", each=" + each + ")");

        if (state.getOutputCollection() != null) {
            System.err.println("[WARN] forEach state '" + name
                    + "': outputCollection has no 1.0 equivalent; value '"
                    + state.getOutputCollection() + "' will be dropped.");
            ReportCollector.get().addIssue(Severity.WARNING, Category.unsupported_feature,
                    "states[" + name + "].outputCollection",
                    "outputCollection has no 1.0 equivalent and will be dropped.",
                    state.getOutputCollection(), null,
                    "Manually implement output collection logic if required.");
        }
        if (state.getBatchSize() > 0) {
            System.err.println("[WARN] forEach state '" + name
                    + "': batchSize has no 1.0 equivalent; value "
                    + state.getBatchSize() + " will be dropped.");
            ReportCollector.get().addIssue(Severity.WARNING, Category.unsupported_feature,
                    "states[" + name + "].batchSize",
                    "batchSize has no 1.0 equivalent and will be dropped.",
                    String.valueOf(state.getBatchSize()), null,
                    "Manually implement batching logic if required.");
        }

        List<Action> actions = state.getActions() != null
                ? state.getActions() : java.util.Collections.emptyList();

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

        return new TaskItem(name, new Task().withForTask(forTask));
    }
}
