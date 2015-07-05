package nz.net.io.jarvis;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.util.Log;

import com.google.android.gms.gcm.GcmListenerService;

import java.util.Calendar;

public class JarvisGcmListenerService extends GcmListenerService {

    @Override
    public void onMessageReceived(String from, Bundle data) {

        String title = data.getString("title");
        String message = data.getString("message");
        String action = data.getString("action");
        Log.d("Jarvis", String.format("Notification received! title: %s message: %s action: %s", title, message, action));

        // Creates an explicit intent for an Activity in your app
        Intent resultIntent = new Intent(this, BaseActivity.class);
        resultIntent.setType(Intent.ACTION_SEARCH);
        resultIntent.putExtra(SearchManager.QUERY, action);

        PendingIntent resultPendingIntent = PendingIntent.getActivity(
                this,
                0,
                resultIntent,
                PendingIntent.FLAG_UPDATE_CURRENT
        );

        // Create notification
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this)
                        .setSmallIcon(R.drawable.app_icon)
                        .setContentTitle(title)
                        .setContentText(message)
                        .setContentIntent(resultPendingIntent)
                        .setLights(0xFF0000ff, 100, 1000) // blue LED, 100ms on, 1s off
                        .setShowWhen(true) // Show time notification arrived
                        .setVibrate(new long[]{1000, 100, 100, 100, 100, 100}); // Wait 1s, 3 quick vibrates of 100ms with 100ms between each


        NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mNotificationManager.notify((int) Calendar.getInstance().getTimeInMillis(), mBuilder.build());
    }
}

