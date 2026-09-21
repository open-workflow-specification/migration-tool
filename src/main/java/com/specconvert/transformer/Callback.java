package com.specconvert.transformer;

import com.specconvert.report.MigrationReport.Category;
import com.specconvert.report.MigrationReport.Severity;
import com.specconvert.report.ReportCollector;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 0.8
import io.serverlessworkflow.api.actions.Action;
import io.serverlessworkflow.api.states.CallbackState;

// 1.0
import io.serverlessworkflow.api.types.AnyEventConsumptionStrategy;
import io.serverlessworkflow.api.types.DoTask;
import io.serverlessworkflow.api.types.EventFilter;
import io.serverlessworkflow.api.types.EventProperties;
import io.serverlessworkflow.api.types.FlowDirective;
import io.serverlessworkflow.api.types.ListenTask;
import io.serverlessworkflow.api.types.ListenTaskConfiguration;
import io.serverlessworkflow.api.types.ListenTo;
import io.serverlessworkflow.api.types.SwitchCase;
import io.serverlessworkflow.api.types.SwitchItem;
import io.serverlessworkflow.api.types.SwitchTask;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskItem;

public class Callback {

    /**
     * Convert a 0.8 callback state to a 1.0 do task
     *
     * The eventRef is resolved against the workflow's top-level event definitions to obtain
     * the CloudEvent type string. If no definition is found, the eventRef name is used as-is.
     *
     * Transition target resolution:
     *   - If the state has a transition, then → the named next state.
     *   - If the state has an end marker, then → "end".
     *   - Otherwise a "TODO" placeholder is emitted with a warning.
     */
    public static TaskItem handleCallback(String name,CallbackState state,Map<String, String> eventTypeByName) {
        return handleCallbackFunction(name, state, eventTypeByName);
    }

    protected static TaskItem handleCallbackFunction(
            String name,
            CallbackState state,
            Map<String, String> eventTypeByName) {

        List<TaskItem> steps = new ArrayList<>();

        ReportCollector.get().addManualTask("high",
                "Review callback state transformation",
                "Callback state '" + name + "' was split into an outgoing call, "
                        + "a listen task, and a conditional switch. Verify the converted "
                        + "flow matches the original callback semantics.",
                "states[" + name + "]");

        // ----------------------------------------------------------------
        // Step 1 — outgoing action (optional)
        // ----------------------------------------------------------------
        Action action = state.getAction();
        if (action != null) {
            steps.add(util.convertAction(action));
        }

        // ----------------------------------------------------------------
        // Step 2 — listen for the callback event
        // ----------------------------------------------------------------
        String eventRef = state.getEventRef() != null ? state.getEventRef() : "callbackEvent";
        String cloudEventType = eventTypeByName.getOrDefault(eventRef, eventRef);

        EventFilter filter = new EventFilter()
                .withWith(new EventProperties().withType(cloudEventType));

        ListenTo listenTo = new ListenTo()
                .withAnyEventConsumptionStrategy(
                        new AnyEventConsumptionStrategy()
                                .withAny(java.util.Collections.singletonList(filter)));

        ListenTask listenTask = new ListenTask()
                .withListen(new ListenTaskConfiguration().withTo(listenTo));

        steps.add(new TaskItem(name + "Listen", new Task().withListenTask(listenTask)));

        // ----------------------------------------------------------------
        // Step 3 — conditional flow after the event
        // ----------------------------------------------------------------
        String transitionTarget = resolveTransition(name, state);

        // Named case: when the expected callback event type is confirmed, go to the transition target
        SwitchCase callbackCase = new SwitchCase()
                .withWhen("${ .type == \"" + cloudEventType + "\" }")
                .withThen(new FlowDirective().withString(transitionTarget));

        // Default case: fallback — end the workflow segment (should not be reached in normal flow)
        SwitchCase defaultCase = new SwitchCase()
                .withThen(new FlowDirective().withString("end"));

        List<SwitchItem> switchItems = new ArrayList<>();
        switchItems.add(new SwitchItem("callbackReceived", callbackCase));
        switchItems.add(new SwitchItem("default", defaultCase));

        SwitchTask switchTask = new SwitchTask().withSwitch(switchItems);
        steps.add(new TaskItem(name + "Route", new Task().withSwitchTask(switchTask)));

        // ----------------------------------------------------------------
        // Wrap all steps in a do task keyed by the state name
        // ----------------------------------------------------------------
        DoTask doTask = new DoTask().withDo(steps);
        return new TaskItem(name, new Task().withDoTask(doTask));
    }

    /**
     * Resolve the 1.0 flow-directive string from the 0.8 state's transition/end fields.
     */
    private static String resolveTransition(String stateName, CallbackState state) {
        if (state.getTransition() != null && state.getTransition().getNextState() != null) {
            return state.getTransition().getNextState();
        }
        if (state.getEnd() != null) {
            return "end";
        }
        System.err.println("[WARN] Callback state '" + stateName
                + "' has no transition or end; emitting 'TODO' placeholder.");
        ReportCollector.get().addIssue(Severity.ERROR, Category.state_transformation,
                "states[" + stateName + "].transition",
                "Callback state has no transition or end; a 'TODO' placeholder was emitted.",
                null, null, "Set the correct next state or end condition.");
        return "TODO";
    }
}
