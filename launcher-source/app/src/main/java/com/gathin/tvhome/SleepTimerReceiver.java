package com.gathin.tvhome;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Control Center's Sleep Timer: an alarm that puts the TV to sleep when it fires. */
public class SleepTimerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        prefs(ctx).edit().remove("sleep_at").apply();
        sleepNow(ctx);
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("control", Context.MODE_PRIVATE);
    }

    /** Sleeps in {@code ms} milliseconds; 0 cancels the timer. A window of a minute keeps it a normal alarm. */
    static void schedule(Context ctx, long ms) {
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, SleepTimerReceiver.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        am.cancel(pi);
        if (ms <= 0) {
            prefs(ctx).edit().remove("sleep_at").apply();
            return;
        }
        long at = System.currentTimeMillis() + ms;
        am.setWindow(AlarmManager.RTC_WAKEUP, at, 60_000L, pi);
        prefs(ctx).edit().putLong("sleep_at", at).apply();
    }

    /** Turns the screen off through the device-admin "lock now" policy. False when that isn't enabled. */
    static boolean sleepNow(Context ctx) {
        DevicePolicyManager dpm = ctx.getSystemService(DevicePolicyManager.class);
        if (dpm == null || !dpm.isAdminActive(new ComponentName(ctx, SleepAdmin.class))) return false;
        dpm.lockNow();
        return true;
    }
}
