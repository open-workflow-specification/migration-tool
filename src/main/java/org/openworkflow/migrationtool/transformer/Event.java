package org.openworkflow.migrationtool.transformer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 0.8
import io.serverlessworkflow.api.actions.Action;
import io.serverlessworkflow.api.events.OnEvents;
import io.serverlessworkflow.api.states.EventState;

// 1.0
import io.serverlessworkflow.api.types.AllEventConsumptionStrategy;
import io.serverlessworkflow.api.types.AnyEventConsumptionStrategy;
import io.serverlessworkflow.api.types.DoTask;
import io.serverlessworkflow.api.types.EventFilter;
import io.serverlessworkflow.api.types.EventProperties;
import io.serverlessworkflow.api.types.FlowDirective;
import io.serverlessworkflow.api.types.ListenTask;
import io.serverlessworkflow.api.types.ListenTaskConfiguration;
import io.serverlessworkflow.api.types.ListenTo;
import io.serverlessworkflow.api.types.SubscriptionIterator;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskItem;

public class Event {
    /**
     * Convert a 0.8 event state to a 1.0 listen task.
     *
     * exclusive mapping:
     *   true  (default) → any:  (first matching event triggers the state)
     *   false           → all:  (all listed events must arrive)
     *
     * Each OnEvents entry contributes one EventFilter per eventRef it lists.
     * The CloudEvent type is resolved from the workflow's top-level event definitions;
     * if no definition is found the eventRef name itself is used as the type.
     *
     * Per-event action association:
     *
     *   exclusive=true:
     *     Only one event arrives. The foreach body must run only the actions associated
     *     with the event that actually arrived.  Each onEvents entry becomes a separate
     *     DoTask in the foreach.do list, guarded by an `if` expression that tests whether
     *     the received event's `.type` matches any of the eventRefs in that entry.
     *     Multiple eventRefs in one entry are OR-ed:
     *       .item.type == "typeA" or .item.type == "typeB"
     *     Tasks whose `if` is false are skipped at runtime, so only the matching group
     *     executes — preserving the 0.8 per-event action association.
     *
     *   exclusive=false:
     *     All events must arrive before actions run.  Because the runtime delivers all
     *     events together, all actions from all onEvents entries should run.  Actions are
     *     still grouped per onEvents entry (each entry becomes its own DoTask), which is
     *     correct and preserves the 0.8 grouping; no if-guard is needed.
     *
     *   If an onEvents entry has no actions, no DoTask is emitted for it.
     *   If no onEvents entry has any actions, foreach is omitted entirely.
     */
    public static TaskItem handleEvent(
            String name,
            EventState state,
            Map<String, String> eventTypeByName) {
        return handleEventFunction(name, state, eventTypeByName);
    }

    protected static TaskItem handleEventFunction(
            String name,
            EventState state,
            Map<String, String> eventTypeByName) {

        List<EventFilter> filters = new ArrayList<>();
        // Each onEvents entry → one DoTask in the foreach body (if it has actions)
        List<TaskItem> foreachGroups = new ArrayList<>();
        boolean exclusive = state.isExclusive();

        if (state.getOnEvents() != null) {
            for (OnEvents onEvent : state.getOnEvents()) {
                List<String> eventRefs = onEvent.getEventRefs() != null
                        ? onEvent.getEventRefs() : java.util.Collections.emptyList();
                List<Action> actions = onEvent.getActions() != null
                        ? onEvent.getActions() : java.util.Collections.emptyList();

                // Collect listen filters — one filter per eventRef
                List<String> cloudEventTypes = new ArrayList<>();
                for (String eventRef : eventRefs) {
                    String cloudEventType = eventTypeByName.getOrDefault(eventRef, eventRef);
                    cloudEventTypes.add(cloudEventType);
                    filters.add(new EventFilter().withWith(new EventProperties().withType(cloudEventType)));
                }

                if (actions.isEmpty()) {
                    continue; // no actions for this entry; skip DoTask generation
                }

                // Build the action task items for this onEvents entry
                List<TaskItem> actionItems = new ArrayList<>();
                for (Action action : actions) {
                    actionItems.add(util.convertAction(action));
                }

                // Determine the key name for this group from the first eventRef (or a fallback)
                String groupName = eventRefs.isEmpty() ? "onEvent" : util.toIdentifier(eventRefs.get(0));

                DoTask groupTask = new DoTask().withDo(actionItems);

                if (exclusive && !cloudEventTypes.isEmpty()) {
                    // Guard: only execute this group when the received event matches one of
                    // the eventRefs in this onEvents entry.
                    groupTask.withIf(buildTypeGuard(cloudEventTypes));
                }
                // exclusive=false: no guard needed — all events have arrived, all actions run

                foreachGroups.add(new TaskItem(groupName, new Task().withDoTask(groupTask)));
            }
        }

        // Build the listen directive
        ListenTo listenTo;
        if (exclusive) {
            listenTo = new ListenTo()
                    .withAnyEventConsumptionStrategy(new AnyEventConsumptionStrategy().withAny(filters));
        } else {
            listenTo = new ListenTo()
                    .withAllEventConsumptionStrategy(new AllEventConsumptionStrategy().withAll(filters));
        }

        ListenTask listenTask = new ListenTask()
                .withListen(new ListenTaskConfiguration().withTo(listenTo));

        // Attach foreach only when there are action groups to dispatch
        if (!foreachGroups.isEmpty()) {
            listenTask.withForeach(new SubscriptionIterator()
                    .withItem("item")
                    .withDo(foreachGroups));
        }

        FlowDirective then = util.resolveThen(name, state);
        if (then != null) {
            listenTask.withThen(then);
        }

        return new TaskItem(name, new Task().withListenTask(listenTask));
    }

    /**
     * Build a jq boolean expression that is true when the received CloudEvent's type
     * matches any of the given type strings.
     *
     * Single type:  .item.type == "com.example.typeA"
     * Multiple:     (.item.type == "com.example.typeA" or .item.type == "com.example.typeB")
     */
    private static String buildTypeGuard(List<String> cloudEventTypes) {
        if (cloudEventTypes.size() == 1) {
            return ".item.type == \"" + cloudEventTypes.get(0) + "\"";
        }
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < cloudEventTypes.size(); i++) {
            if (i > 0) sb.append(" or ");
            sb.append(".item.type == \"").append(cloudEventTypes.get(i)).append("\"");
        }
        sb.append(")");
        return sb.toString();
    }
}
