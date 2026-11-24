package com.example.yapit;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;

public final class YapitApp extends Application {
    public static final String GENERATION_CHANNEL = "voice_generation";
    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(GENERATION_CHANNEL, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Progress and completion for generated speech");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
}
