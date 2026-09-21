package com.specconvert.transformer;

import java.util.ArrayList;

import java.util.List;

// 0.8
import io.serverlessworkflow.api.actions.Action;
import io.serverlessworkflow.api.states.OperationState;

// 1.0
import io.serverlessworkflow.api.types.ForkTask;
import io.serverlessworkflow.api.types.ForkTaskConfiguration;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskItem;

public class Operation {
    /**
     * Convert a 0.8 operation state to one or more 1.0 tasks.
     *
     * actionMode mapping:
     *   sequential (default) → each action becomes an individual call task wrapped in
     *                          a do task keyed by the state name, preserving execution order.
     *   parallel             → actions are placed as branches inside a fork task keyed by
     *                          the state name (compete: false — all branches must finish).
     *
     * Each action's functionRef becomes a call task:
     *   { "<refName>": { call: "<refName>", with: { <arguments> } } }
     */
    public static TaskItem handleOperation(String name, OperationState state) {
        return handleOperationFunction(name, state);
    }

    protected static TaskItem handleOperationFunction(String name, OperationState state) {
        List<Action> actions = state.getActions() != null ? state.getActions() : java.util.Collections.emptyList();

        boolean parallel = state.getActionMode() == OperationState.ActionMode.PARALLEL;
        System.err.println("[INFO] Converting operation state '" + name + "' (actionMode="
                + (parallel ? "parallel" : "sequential") + ", actions=" + actions.size() + ")");

        if (parallel) {
            // parallel → fork task; each action becomes its own branch
            List<TaskItem> branchItems = new ArrayList<>();
            for (Action action : actions) {
                TaskItem actionItem = util.convertAction(action);
                io.serverlessworkflow.api.types.DoTask doTask =
                        new io.serverlessworkflow.api.types.DoTask()
                                .withDo(java.util.Collections.singletonList(actionItem));
                String branchName = actionItem.getName() != null ? actionItem.getName() : "branch";
                branchItems.add(new TaskItem(branchName, new Task().withDoTask(doTask)));
            }
            ForkTaskConfiguration forkCfg = new ForkTaskConfiguration()
                    .withCompete(false)
                    .withBranches(branchItems);
            return new TaskItem(name, new Task().withForkTask(new ForkTask().withFork(forkCfg)));
        }

        // sequential → do task containing each action as a call task in order
        List<TaskItem> actionItems = new ArrayList<>();
        for (Action action : actions) {
            actionItems.add(util.convertAction(action));
        }
        io.serverlessworkflow.api.types.DoTask doTask =
                new io.serverlessworkflow.api.types.DoTask().withDo(actionItems);
        return new TaskItem(name, new Task().withDoTask(doTask));
    }
}
