package com.studentplanner.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("rem", "Reminders", NotificationManager.IMPORTANCE_HIGH));
            b = new Notification.Builder(c, "rem");
        } else {
            b = new Notification.Builder(c);
        }
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.ic_popup_reminder)
         .setContentTitle("Upcoming")
         .setContentText(i.getStringExtra("t"))
         .setAutoCancel(true)
         .setContentIntent(open);
        nm.notify(i.getIntExtra("i", 0), b.build());
    }
}
