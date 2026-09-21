package com.specconvert.transformer;

import java.util.ArrayList;
import java.util.List;

// 0.8
import io.serverlessworkflow.api.actions.Action;
import io.serverlessworkflow.api.branches.Branch;
import io.serverlessworkflow.api.states.ParallelState;

// 1.0
import io.serverlessworkflow.api.types.ForkTask;
import io.serverlessworkflow.api.types.ForkTaskConfiguration;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskItem;

public class Parallel {
    /**
     * Convert a 0.8 parallel state to a 1.0 fork task.
     *
     * completionType mapping:
     *   allOf   → compete: false  (all branches must finish)
     *   atLeast → compete: true   (first N to complete wins; 1.0 models this as compete)
     */
    public static TaskItem handleParallel(String name, ParallelState state) {
        return handleParallelFunction(name, state);
    }

    protected static TaskItem handleParallelFunction(String name, ParallelState state) {
        // compete: true when only a subset needs to complete (atLeast)
        boolean compete = state.getCompletionType() == ParallelState.CompletionType.AT_LEAST;

        List<TaskItem> branchItems = new ArrayList<>();

        if (state.getBranches() != null) {
            for (Branch branch : state.getBranches()) {
                String branchName = branch.getName() != null ? branch.getName() : "branch";
                List<TaskItem> actionItems = new ArrayList<>();

                if (branch.getActions() != null) {
                    for (Action action : branch.getActions()) {
                        actionItems.add(util.convertAction(action));
                    }
                }

                // Each branch becomes a TaskItem whose value is a DoTask containing its actions
                io.serverlessworkflow.api.types.DoTask doTask =
                        new io.serverlessworkflow.api.types.DoTask()
                                .withDo(actionItems);
                branchItems.add(new TaskItem(branchName, new Task().withDoTask(doTask)));
            }
        }

        ForkTaskConfiguration forkCfg = new ForkTaskConfiguration()
                .withCompete(compete)
                .withBranches(branchItems);

        ForkTask forkTask = new ForkTask().withFork(forkCfg);
        return new TaskItem(name, new Task().withForkTask(forkTask));
    }
}
