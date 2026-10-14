package ru.fieldmap.app;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        String id = intent.getStringExtra("id");
        String title = intent.getStringExtra("title");
        String text = intent.getStringExtra("text");
        int code = intent.getIntExtra("code", 0);
        try {
            Intent open = new Intent(ctx, MainActivity.class);
            open.putExtra("nodeId", id == null ? "" : id);
            open.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(ctx, code + 70000, open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = new Notification.Builder(ctx, Reminders.CHANNEL_ID)
                    .setContentTitle(title == null || title.isEmpty() ? "Напоминание" : title)
                    .setContentText(text == null ? "" : text)
                    .setStyle(new Notification.BigTextStyle().bigText(text == null ? "" : text))
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setDefaults(Notification.DEFAULT_ALL);
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(code + 70000, b.build());
        } catch (Exception ignored) {}
        if (id != null) Reminders.markFired(ctx, id);
    }
}
