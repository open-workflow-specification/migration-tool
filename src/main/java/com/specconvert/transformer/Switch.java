package com.specconvert.transformer;

import com.specconvert.report.MigrationReport.Category;
import com.specconvert.report.MigrationReport.Severity;
import com.specconvert.report.ReportCollector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 0.8
import io.serverlessworkflow.api.states.SwitchState;
import io.serverlessworkflow.api.switchconditions.DataCondition;
import io.serverlessworkflow.api.switchconditions.EventCondition;
import io.serverlessworkflow.api.defaultdef.DefaultConditionDefinition;

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

public class Switch {

    public static TaskItem handleSwitch(String name, SwitchState state,
                                        Map<String, String> eventTypeByName) {
        return handleSwitchFunction(name, state, eventTypeByName);
    }

    protected static TaskItem handleSwitchFunction(String name, SwitchState state,
                                                   Map<String, String> eventTypeByName) {
        // Event-based switch → listen (with per-event filters) + switch (trivial .type checks)
        if (state.getEventConditions() != null && !state.getEventConditions().isEmpty()) {
            return handleEventSwitch(name, state, eventTypeByName);
        }

        // Data-based switch → plain switch task (unchanged)
        return handleDataSwitch(name, state);
    }

    // ------------------------------------------------------------------
    // Event-based switch: listen + switch composite
    // ------------------------------------------------------------------

    /**
     * A 0.8 event-switch waits for one of N events and routes based on which arrived.
     *
     * 1.0 translation:
     *   <name>:
     *     do:
     *       - <name>Listen:
     *           listen:
     *             to:
     *               any:
     *                 - with: { type: "<cloudEventType1>" }
     *                 - with: { type: "<cloudEventType2>" }
     *       - <name>Route:
     *           switch:
     *             - <caseName>:
     *                 when: .type == "<cloudEventType1>"
     *                 then: <nextState>
     *             - default:
     *                 then: <defaultNextState>
     *
     * The listen task blocks until any of the listed CloudEvent types arrives and places
     * the received event in the task output. The switch then performs a trivial .type
     * equality check on that output to route to the correct next state.
     */
    private static TaskItem handleEventSwitch(String name, SwitchState state,
                                              Map<String, String> eventTypeByName) {
        List<EventFilter> filters   = new ArrayList<>();
        List<SwitchItem> switchItems = new ArrayList<>();

        for (EventCondition cond : state.getEventConditions()) {
            String eventRef       = cond.getEventRef() != null ? cond.getEventRef() : "event";
            String cloudEventType = eventTypeByName.getOrDefault(eventRef, eventRef);
            String caseName       = util.toIdentifier(eventRef);
            String nextState      = resolveEventConditionTarget(name, eventRef, cond);

            // One listen filter per eventRef
            filters.add(new EventFilter()
                    .withWith(new EventProperties().withType(cloudEventType)));

            // Trivial switch case: the received event's .type matches this CloudEvent type
            switchItems.add(new SwitchItem(caseName,
                    new SwitchCase()
                            .withWhen(".type == \"" + cloudEventType + "\"")
                            .withThen(new FlowDirective().withString(nextState))));
        }

        // Default condition
        DefaultConditionDefinition def = state.getDefaultCondition();
        if (def != null) {
            String nextState = util.transitionName(def.getTransition());
            switchItems.add(new SwitchItem("default",
                    new SwitchCase()
                            .withThen(new FlowDirective().withString(nextState))));
        }

        // Build listen task: any of the listed event types unblocks it
        ListenTo listenTo = new ListenTo()
                .withAnyEventConsumptionStrategy(
                        new AnyEventConsumptionStrategy().withAny(filters));
        ListenTask listenTask = new ListenTask()
                .withListen(new ListenTaskConfiguration().withTo(listenTo));

        // Build switch task
        SwitchTask switchTask = new SwitchTask().withSwitch(switchItems);

        // Wrap both in a do task keyed by the state name
        List<TaskItem> steps = List.of(
                new TaskItem(name + "Listen", new Task().withListenTask(listenTask)),
                new TaskItem(name + "Route",  new Task().withSwitchTask(switchTask)));

        DoTask doTask = new DoTask().withDo(steps);
        return new TaskItem(name, new Task().withDoTask(doTask));
    }

    /**
     * Resolve the 1.0 flow-directive string for a single EventCondition.
     * An EventCondition may have a transition OR an end marker.
     */
    private static String resolveEventConditionTarget(String stateName, String eventRef,
                                                      EventCondition cond) {
        if (cond.getTransition() != null && cond.getTransition().getNextState() != null) {
            return cond.getTransition().getNextState();
        }
        if (cond.getEnd() != null) {
            return "end";
        }
        System.err.println("[WARN] Event condition '" + eventRef + "' in switch state '"
                + stateName + "' has no transition or end; emitting 'TODO' placeholder.");
        ReportCollector.get().addIssue(Severity.WARNING, Category.state_transformation,
                "states[" + stateName + "].eventConditions[" + eventRef + "].transition",
                "Event condition has no transition or end; a 'TODO' placeholder was emitted.",
                null, null, "Set the correct next state or end condition.");
        return "TODO";
    }

    // ------------------------------------------------------------------
    // Data-based switch: plain switch task
    // ------------------------------------------------------------------

    private static TaskItem handleDataSwitch(String name, SwitchState state) {
        List<SwitchItem> switchItems = new ArrayList<>();

        if (state.getDataConditions() != null) {
            for (DataCondition cond : state.getDataConditions()) {
                String caseName      = cond.getName() != null ? util.toIdentifier(cond.getName()) : "case";
                String rawExpression = cond.getCondition() != null ? cond.getCondition() : "TODO";
                String nextState     = util.transitionName(cond.getTransition());

                String strippedExpression = util.stripExpressionWrapper(rawExpression);
                switchItems.add(new SwitchItem(caseName,
                        new SwitchCase()
                                .withWhen(strippedExpression)
                                .withThen(new FlowDirective().withString(nextState))));
            }
        }

        // Default condition
        DefaultConditionDefinition def = state.getDefaultCondition();
        if (def != null) {
            String nextState = util.transitionName(def.getTransition());
            switchItems.add(new SwitchItem("default",
                    new SwitchCase()
                            .withThen(new FlowDirective().withString(nextState))));
        }

        SwitchTask switchTask = new SwitchTask().withSwitch(switchItems);
        return new TaskItem(name, new Task().withSwitchTask(switchTask));
    }
}
