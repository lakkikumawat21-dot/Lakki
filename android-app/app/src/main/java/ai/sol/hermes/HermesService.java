package ai.sol.hermes;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * Background Foreground Service to maintain the Hermes Agent capability bridge
 * and internal PRoot Linux engine running.
 */
public class HermesService extends Service {
    private static final String TAG = "HermesService";
    private static final int SERVICE_NOTIFICATION_ID = 43097;
    public static final String CHANNEL_ID = "hermes_service_channel";

    private CapabilityServer capabilityServer;
    private PRootRunner prootRunner;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        try {
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Hermes Agent Engine")
                    .setContentText("Local Linux PRoot engine & capability bridge active")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= 34) { // Android 14 (API 34)
                try {
                    startForeground(
                            SERVICE_NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    );
                } catch (Throwable t) {
                    Log.w(TAG, "startForeground with SPECIAL_USE failed, falling back: " + t.getMessage());
                    startForeground(SERVICE_NOTIFICATION_ID, notification);
                }
            } else {
                startForeground(SERVICE_NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground failed gracefully: " + t.getMessage(), t);
        }

        // 1. Start Capability REST server on port 43097
        try {
            capabilityServer = new CapabilityServer(this, CapabilityServer.DEFAULT_PORT);
            capabilityServer.start();
            Log.i(TAG, "CapabilityServer started on port " + CapabilityServer.DEFAULT_PORT);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start CapabilityServer: " + t.getMessage(), t);
        }

        // 2. Start internal PRoot Linux engine if installed
        if (InstallationManager.isInstalled(this)) {
            try {
                prootRunner = PRootRunner.getInstance(this);
                prootRunner.start();
                Log.i(TAG, "Internal PRoot engine started.");
            } catch (Throwable t) {
                Log.e(TAG, "Failed to launch PRoot runner: " + t.getMessage(), t);
            }
        } else {
            Log.i(TAG, "Hermes subsystem not installed yet. Waiting for setup.");
        }
    }

    private void createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Hermes Agent Service",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Keeps the Hermes agent capability server and PRoot engine running");
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to create service notification channel: " + t.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_START_ENGINE".equals(intent.getAction())) {
            if (InstallationManager.isInstalled(this)) {
                if (prootRunner == null) {
                    prootRunner = PRootRunner.getInstance(this);
                }
                prootRunner.start();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (prootRunner != null) {
            prootRunner.stop();
        }
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
