package ai.sol.hermes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Background Foreground Service to maintain the Hermes Agent capability bridge alive.
 */
public class HermesService extends Service {
    private static final String TAG = "HermesService";
    private static final int SERVICE_NOTIFICATION_ID = 43097;
    private static final String CHANNEL_ID = "hermes_service_channel";

    private CapabilityServer capabilityServer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Hermes Agent Engine")
                .setContentText("Local capability bridge active on port 43097")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(SERVICE_NOTIFICATION_ID, notification);

        capabilityServer = new CapabilityServer(this, CapabilityServer.DEFAULT_PORT);
        capabilityServer.start();
        Log.i(TAG, "HermesService created and CapabilityServer started.");
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Hermes Agent Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps the Hermes agent capability server running");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (capabilityServer != null) {
            capabilityServer.stop();
        }
        Log.i(TAG, "HermesService destroyed.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
