package org.openworkflow.migrationtool.transformer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 0.8
import io.serverlessworkflow.api.states.SleepState;

// 1.0
import io.serverlessworkflow.api.types.DurationInline;
import io.serverlessworkflow.api.types.FlowDirective;
import io.serverlessworkflow.api.types.Task;
import io.serverlessworkflow.api.types.TaskItem;
import io.serverlessworkflow.api.types.TimeoutAfter;
import io.serverlessworkflow.api.types.WaitTask;


public class Sleep {
    /**
     * Convert a 0.8 sleep state to a 1.0 TaskItem containing a wait task.
     *
     * The ISO 8601 duration string (e.g. "P2DT3H4M") is parsed into a DurationInline:
     * { "wait": { "days": 2, "hours": 3, "minutes": 4 } }
     *
     * Years and months are folded into days (approximate: 1y=365d, 1mo=30d)
     * because DurationInline has no year/month fields.
     *
     * The 0.8 transition/end fields are carried over as a 1.0 then directive.
     */
    public static TaskItem handleSleep(String name, SleepState state) {
        return handleSleepFunction(name, state);
    }

    /**
     * @deprecated Use {@link #handleSleep(String, SleepState)} instead.
     *             Retained for any existing callers that consume only the WaitTask.
     */
    @Deprecated
    public static WaitTask handleWait(SleepState state) {
        return buildWaitTask(state);
    }

    private static TaskItem handleSleepFunction(String name, SleepState state) {
        WaitTask waitTask = buildWaitTask(state);
        FlowDirective then = util.resolveThen(name, state);
        if (then != null) {
            waitTask.withThen(then);
        }
        return new TaskItem(name, new Task().withWaitTask(waitTask));
    }

    private static WaitTask buildWaitTask(SleepState state) {
        String iso8601Duration = state.getDuration();
        if (iso8601Duration == null) iso8601Duration = "PT0S";

        Pattern pattern = Pattern.compile(
            "P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?"
        );
        Matcher m = pattern.matcher(iso8601Duration);

        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid ISO 8601 duration: " + iso8601Duration);
        }

        int years   = m.group(1) != null ? Integer.parseInt(m.group(1)) : 0;
        int months  = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
        int days    = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        int hours   = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
        int minutes = m.group(5) != null ? Integer.parseInt(m.group(5)) : 0;
        int seconds = m.group(6) != null ? Integer.parseInt(m.group(6)) : 0;

        // DurationInline has no year/month fields — fold into days (approximate)
        int totalDays = days + years * 365 + months * 30;

        DurationInline dur = new DurationInline()
                .withDays(totalDays)
                .withHours(hours)
                .withMinutes(minutes)
                .withSeconds(seconds);

        return new WaitTask().withWait(new TimeoutAfter().withDurationInline(dur));
    }
}
