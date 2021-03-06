package com.todoroo.astrid.repeats;

import java.text.ParseException;
import java.util.Collections;
import java.util.Date;
import java.util.TimeZone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.ical.iter.RecurrenceIterator;
import com.google.ical.iter.RecurrenceIteratorFactory;
import com.google.ical.values.DateTimeValueImpl;
import com.google.ical.values.DateValue;
import com.google.ical.values.DateValueImpl;
import com.google.ical.values.Frequency;
import com.google.ical.values.RRule;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.utility.DateUtilities;
import com.todoroo.astrid.api.AstridApiConstants;
import com.todoroo.astrid.core.PluginServices;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.service.StatisticsService;
import com.todoroo.astrid.utility.Flags;

public class RepeatTaskCompleteListener extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        ContextManager.setContext(context);
        long taskId = intent.getLongExtra(AstridApiConstants.EXTRAS_TASK_ID, -1);
        if(taskId == -1)
            return;

        Task task = PluginServices.getTaskService().fetchById(taskId, Task.ID, Task.RECURRENCE,
                Task.DUE_DATE, Task.FLAGS, Task.HIDE_UNTIL, Task.REMOTE_ID);
        if(task == null)
            return;

        // don't repeat when it repeats on the server
        if(task.getValue(Task.REMOTE_ID) > 0)
            return;

        String recurrence = task.getValue(Task.RECURRENCE);
        if(recurrence != null && recurrence.length() > 0) {
            long newDueDate;
            try {
                newDueDate = computeNextDueDate(task, recurrence);
                if(newDueDate == -1)
                    return;
            } catch (ParseException e) {
                PluginServices.getExceptionService().reportError("repeat-parse", e); //$NON-NLS-1$
                return;
            }

            StatisticsService.reportEvent("v2-task-repeat"); //$NON-NLS-1$

            long hideUntil = task.getValue(Task.HIDE_UNTIL);
            if(hideUntil > 0 && task.getValue(Task.DUE_DATE) > 0) {
                hideUntil += newDueDate - task.getValue(Task.DUE_DATE);
            }

            // clone to create new task
            Task clone = PluginServices.getTaskService().clone(task);
            clone.setValue(Task.DUE_DATE, newDueDate);
            clone.setValue(Task.HIDE_UNTIL, hideUntil);
            clone.setValue(Task.COMPLETION_DATE, 0L);
            clone.setValue(Task.TIMER_START, 0L);
            clone.setValue(Task.ELAPSED_SECONDS, 0);
            clone.setValue(Task.REMINDER_SNOOZE, 0L);
            PluginServices.getTaskService().save(clone);

            // clear recurrence from completed task so it can be re-completed
            task.setValue(Task.RECURRENCE, ""); //$NON-NLS-1$
            task.setValue(Task.DETAILS_DATE, 0L);
            PluginServices.getTaskService().save(task);

            // send a broadcast
            Intent broadcastIntent = new Intent(AstridApiConstants.BROADCAST_EVENT_TASK_REPEATED);
            broadcastIntent.putExtra(AstridApiConstants.EXTRAS_TASK_ID, clone.getId());
            broadcastIntent.putExtra(AstridApiConstants.EXTRAS_OLD_DUE_DATE, task.getValue(Task.DUE_DATE));
            broadcastIntent.putExtra(AstridApiConstants.EXTRAS_NEW_DUE_DATE, newDueDate);
            context.sendOrderedBroadcast(broadcastIntent, null);
            Flags.set(Flags.REFRESH);
        }
    }

    /** Compute next due date */
    public static long computeNextDueDate(Task task, String recurrence) throws ParseException {
        boolean repeatAfterCompletion = task.getFlag(Task.FLAGS, Task.FLAG_REPEAT_AFTER_COMPLETION);
        RRule rrule = initRRule(recurrence);

        // initialize startDateAsDV
        Date original = setUpStartDate(task, repeatAfterCompletion);
        DateValue startDateAsDV = setUpStartDateAsDV(task, rrule, original, repeatAfterCompletion);

        if(rrule.getFreq() == Frequency.HOURLY)
            return handleHourlyRepeat(original, rrule);
        else
            return invokeRecurrence(rrule, original, startDateAsDV);
    }

    private static long invokeRecurrence(RRule rrule, Date original,
            DateValue startDateAsDV) {
        long newDueDate = -1;
        RecurrenceIterator iterator = RecurrenceIteratorFactory.createRecurrenceIterator(rrule,
                startDateAsDV, TimeZone.getDefault());
        DateValue nextDate = startDateAsDV;

        for(int i = 0; i < 10; i++) { // ten tries then we give up
            if(!iterator.hasNext())
                return -1;
            nextDate = iterator.next();

            if(nextDate.compareTo(startDateAsDV) == 0)
                continue;

            newDueDate = buildNewDueDate(original, nextDate);

            // detect if we finished
            if(newDueDate > original.getTime())
                break;
        }
        return newDueDate;
    }

    /** Compute long due date from DateValue */
    private static long buildNewDueDate(Date original, DateValue nextDate) {
        long newDueDate;
        if(nextDate instanceof DateTimeValueImpl) {
            DateTimeValueImpl newDateTime = (DateTimeValueImpl)nextDate;
            Date date = new Date(Date.UTC(newDateTime.year() - 1900, newDateTime.month() - 1,
                    newDateTime.day(), newDateTime.hour(),
                    newDateTime.minute(), newDateTime.second()));
            // time may be inaccurate due to DST, force time to be same
            date.setHours(original.getHours());
            date.setMinutes(original.getMinutes());
            newDueDate = Task.createDueDate(Task.URGENCY_SPECIFIC_DAY_TIME,
                    date.getTime());
        } else {
            newDueDate = Task.createDueDate(Task.URGENCY_SPECIFIC_DAY,
                    new Date(nextDate.year() - 1900, nextDate.month() - 1,
                            nextDate.day()).getTime());
        }
        return newDueDate;
    }

    /** Initialize RRule instance */
    private static RRule initRRule(String recurrence) throws ParseException {
        RRule rrule = new RRule(recurrence);

        // handle the iCalendar "byDay" field differently depending on if
        // we are weekly or otherwise
        if(rrule.getFreq() != Frequency.WEEKLY)
            rrule.setByDay(Collections.EMPTY_LIST);

        return rrule;
    }

    /** Set up repeat start date */
    private static Date setUpStartDate(Task task, boolean repeatAfterCompletion) {
        Date startDate = new Date();
        if(task.hasDueDate()) {
            Date dueDate = new Date(task.getValue(Task.DUE_DATE));
            if(!repeatAfterCompletion)
                startDate = dueDate;
            else if(task.hasDueTime()) {
                startDate.setHours(dueDate.getHours());
                startDate.setMinutes(dueDate.getMinutes());
            }
        }
        return startDate;
    }

    private static DateValue setUpStartDateAsDV(Task task, RRule rrule, Date startDate,
            boolean repeatAfterCompletion) {

        // if repeat after completion with weekdays, pre-compute
        if(repeatAfterCompletion && rrule.getByDay().size() > 0) {
            startDate = new Date(startDate.getTime() + DateUtilities.ONE_WEEK * rrule.getInterval() -
                    DateUtilities.ONE_DAY);
            rrule.setInterval(1);
        }

        if(task.hasDueTime())
            return new DateTimeValueImpl(startDate.getYear() + 1900,
                    startDate.getMonth() + 1, startDate.getDate(),
                    startDate.getHours(), startDate.getMinutes(), startDate.getSeconds());
        else
            return new DateValueImpl(startDate.getYear() + 1900,
                    startDate.getMonth() + 1, startDate.getDate());
    }

    private static long handleHourlyRepeat(Date startDate, RRule rrule) {
        long newDueDate;
        newDueDate = Task.createDueDate(Task.URGENCY_SPECIFIC_DAY_TIME,
                startDate.getTime() + DateUtilities.ONE_HOUR * rrule.getInterval());
        return newDueDate;
    }

}
