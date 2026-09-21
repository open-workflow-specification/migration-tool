package com.specconvert.transformer;

import java.util.ArrayList;
import java.util.Map;

import java.util.List;

// 0.8
import io.serverlessworkflow.api.actions.Action;
import io.serverlessworkflow.api.events.OnEvents;
import io.serverlessworkflow.api.states.EventState;

// 1.0
import io.serverlessworkflow.api.types.AllEventConsumptionStrategy;
import io.serverlessworkflow.api.types.AnyEventConsumptionStrategy;
import io.serverlessworkflow.api.types.EventFilter;
import io.serverlessworkflow.api.types.EventProperties;
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
     * Actions mapping via foreach:
     *   1.0 ListenTask carries a `foreach` (SubscriptionIterator) whose `do` list
     *   executes for every consumed event. The iterator variable "item" holds
     *   the received CloudEvent, so actions can inspect it.
     *
     *   All onEvents actions are flattened into a single do list. If different onEvents
     *   entries have different actions, each distinct action list is appended in order.
     *   If no onEvents entries have any actions, foreach is omitted entirely.
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

        // Collect all actions across onEvents entries for the foreach do list
        List<Action> allActions = new ArrayList<>();

        if (state.getOnEvents() != null) {
            for (OnEvents onEvent : state.getOnEvents()) {
                List<Action> actions = onEvent.getActions() != null
                        ? onEvent.getActions() : java.util.Collections.emptyList();

                if (onEvent.getEventRefs() != null) {
                    for (String eventRef : onEvent.getEventRefs()) {
                        String cloudEventType = eventTypeByName.getOrDefault(eventRef, eventRef);
                        EventProperties props = new EventProperties().withType(cloudEventType);
                        filters.add(new EventFilter().withWith(props));
                    }
                }
                allActions.addAll(actions);
            }
        }

        // exclusive=true → any (first matching event wins); exclusive=false → all (must all arrive)
        ListenTo listenTo;
        if (state.isExclusive()) {
            listenTo = new ListenTo()
                    .withAnyEventConsumptionStrategy(new AnyEventConsumptionStrategy().withAny(filters));
        } else {
            listenTo = new ListenTo()
                    .withAllEventConsumptionStrategy(new AllEventConsumptionStrategy().withAll(filters));
        }

        ListenTask listenTask = new ListenTask()
                .withListen(new ListenTaskConfiguration().withTo(listenTo));

        // Build the foreach iterator only when at least one onEvents entry has actions
        if (!allActions.isEmpty()) {
            List<TaskItem> foreachDo = new ArrayList<>();
            for (Action action : allActions) {
                foreachDo.add(util.convertAction(action));
            }
            // item = the variable name that holds each received CloudEvent inside foreach.do
            listenTask.withForeach(new SubscriptionIterator()
                    .withItem("item")
                    .withDo(foreachDo));
        }

        return new TaskItem(name, new Task().withListenTask(listenTask));
    }
}
