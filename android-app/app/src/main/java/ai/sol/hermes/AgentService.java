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
 * Foreground AgentService supervising the PRoot Linux process,
 * CapabilityServer, and AiForwardProxy.
 * Reconstructed from com.xermes.xermes.AgentService.
 */
public class AgentService extends Service {
    private static final String TAG = "AgentService";
    private static final int NOTIFICATION_ID = 43097;
    public static final String CHANNEL_ID = "hermes_agent_service_channel";

    private CapabilityServer capabilityServer;
    private AiForwardProxy forwardProxy;
    private UbuntuManager ubuntuManager;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        try {
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Hermes Agent Engine")
                    .setContentText("PRoot Linux Subsystem & Bridge Active")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    );
                } catch (Throwable t) {
                    startForeground(NOTIFICATION_ID, notification);
                }
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Foreground start notice: " + t.getMessage());
        }

        // 1. Initialize Capability Server (Port 43097)
        try {
            capabilityServer = new CapabilityServer(this, HermesAgentPaths.CAPABILITY_PORT);
            capabilityServer.start();
        } catch (Throwable t) {
            Log.e(TAG, "Failed starting capability server: " + t.getMessage());
        }

        // 2. Initialize Forward Proxy (Port 39068)
        try {
            forwardProxy = AiForwardProxy.getInstance(this);
            forwardProxy.start();
        } catch (Throwable t) {
            Log.d(TAG, "Proxy start notice: " + t.getMessage());
        }

        // 3. Initialize & Launch PRoot backend via UbuntuManager
        ubuntuManager = UbuntuManager.getInstance(this);
        if (ubuntuManager.isRootfsReady()) {
            ubuntuManager.startBackend();
        } else {
            Log.i(TAG, "Rootfs not ready yet. Waiting for setup completion.");
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Hermes Agent Engine Service",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Supervises the Hermes Agent PRoot Linux subsystem");
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_START_BACKEND".equals(intent.getAction())) {
            if (ubuntuManager != null && ubuntuManager.isRootfsReady()) {
                ubuntuManager.startBackend();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (ubuntuManager != null) {
            ubuntuManager.stopBackend();
        }
        if (forwardProxy != null) {
            forwardProxy.stop();
        }
        if (capabilityServer != null) {
            capabilityServer.stop();
        }
        Log.i(TAG, "AgentService destroyed.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
