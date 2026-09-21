package com.specconvert.transformer;

import io.serverlessworkflow.api.states.InjectState;
import io.serverlessworkflow.api.types.Set;
import io.serverlessworkflow.api.types.SetTask;
import io.serverlessworkflow.api.types.SetTaskConfiguration;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskItem;

public class Inject {
    public static TaskItem handleInject(String name, InjectState state) {
        return handleInjectFunction(name, state);
    }
    
    protected static TaskItem handleInjectFunction(String name, InjectState state) {
        SetTaskConfiguration cfg = new SetTaskConfiguration();

        if (state.getData() != null && state.getData().isObject()) {
            state.getData().fields().forEachRemaining(entry ->
                    cfg.setAdditionalProperty(entry.getKey(), entry.getValue())
            );
        }

        SetTask setTask = new SetTask().withSet(new Set().withSetTaskConfiguration(cfg));
        return new TaskItem(name, new Task().withSetTask(setTask));
    }
}
