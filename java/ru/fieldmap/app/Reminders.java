package ru.fieldmap.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class Reminders {
    public static final String CHANNEL_ID = "fieldmap_reminders";
    static final String PREFS = "reminders";

    public static synchronized void schedule(Context ctx, String id, String title, String text, long when) {
        if (id == null || when <= System.currentTimeMillis()) return;
        Map<String, JSONObject> all = load(ctx);
        JSONObject old = all.get(id);
        int code = (old != null) ? old.optInt("code", 0) : nextCode(ctx);
        try {
            JSONObject o = new JSONObject();
            o.put("when", when);
            o.put("title", title == null ? "" : title);
            o.put("text", text == null ? "" : text);
            o.put("code", code);
            all.put(id, o);
            save(ctx, all);
        } catch (Exception ignored) {}
        cancelAlarm(ctx, code);
        setAlarm(ctx, id, title, text, when, code);
    }

    public static synchronized void cancel(Context ctx, String id) {
        Map<String, JSONObject> all = load(ctx);
        JSONObject o = all.remove(id);
        if (o == null) return;
        save(ctx, all);
        cancelAlarm(ctx, o.optInt("code", 0));
    }

    public static synchronized void markFired(Context ctx, String id) {
        Map<String, JSONObject> all = load(ctx);
        if (all.remove(id) != null) save(ctx, all);
    }

    public static synchronized void rescheduleAll(Context ctx) {
        Map<String, JSONObject> all = load(ctx);
        long now = System.currentTimeMillis();
        for (Map.Entry<String, JSONObject> e : all.entrySet()) {
            JSONObject o = e.getValue();
            long when = o.optLong("when", 0);
            if (when > now) {
                setAlarm(ctx, e.getKey(), o.optString("title"), o.optString("text"), when, o.optInt("code", 0));
            }
        }
    }

    static void setAlarm(Context ctx, String id, String title, String text, long when, int code) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null || code == 0) return;
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.putExtra("id", id); i.putExtra("title", title); i.putExtra("text", text); i.putExtra("code", code);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, code, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        boolean exact = true;
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) exact = false;
        if (exact) {
            try {
                if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
                else am.setExact(AlarmManager.RTC_WAKEUP, when, pi);
            } catch (SecurityException e) { exact = false; }
        }
        if (!exact) am.set(AlarmManager.RTC_WAKEUP, when, pi);
    }

    static void cancelAlarm(Context ctx, int code) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null || code == 0) return;
        Intent i = new Intent(ctx, ReminderReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, code, i,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) { am.cancel(pi); pi.cancel(); }
    }

    static Map<String, JSONObject> load(Context ctx) {
        Map<String, JSONObject> out = new HashMap<>();
        try {
            String s = ctx.getSharedPreferences(PREFS, 0).getString("map", "");
            if (s != null && s.length() > 0) {
                JSONObject root = new JSONObject(s);
                Iterator<String> it = root.keys();
                while (it.hasNext()) { String k = it.next(); out.put(k, root.getJSONObject(k)); }
            }
        } catch (Exception ignored) {}
        return out;
    }

    static void save(Context ctx, Map<String, JSONObject> all) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, JSONObject> e : all.entrySet()) root.put(e.getKey(), e.getValue());
            ctx.getSharedPreferences(PREFS, 0).edit().putString("map", root.toString()).apply();
        } catch (Exception ignored) {}
    }

    static int nextCode(Context ctx) {
        int c = ctx.getSharedPreferences(PREFS, 0).getInt("codeSeq", 100) + 1;
        ctx.getSharedPreferences(PREFS, 0).edit().putInt("codeSeq", c).apply();
        return c;
    }
}
