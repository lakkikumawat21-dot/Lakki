package ai.sol.hermes;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 100% Ad-Free, zero-tracker Local Android Capability REST Server.
 * Serves port 43097 for Hermes Agent's android_device.py capability integration.
 * Strictly executes all socket binding and networking off the UI Main Thread.
 */
public class CapabilityServer {
    private static final String TAG = "HermesCapabilityServer";
    public static final int DEFAULT_PORT = 43097;
    public static final String CHANNEL_ID = "hermes_agent_notifications";

    private final Context context;
    private final int port;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public CapabilityServer(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
        initNotificationChannel();
    }

    private void initNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Hermes Agent Alerts",
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                channel.setDescription("Notifications and alarms dispatched by Hermes Agent");
                NotificationManager manager = context.getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Notification channel init skipped or not supported: " + t.getMessage());
        }
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        executor.execute(() -> {
            try {
                // Strictly bind loopback (127.0.0.1) off the main thread
                InetAddress loopback = InetAddress.getByName("127.0.0.1");
                serverSocket = new ServerSocket(port, 50, loopback);
                Log.i(TAG, "Capability REST server listening on 127.0.0.1:" + port);
                while (isRunning && serverSocket != null && !serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    socket.setSoTimeout(10000); // 10s socket timeout
                    executor.execute(() -> handleClient(socket));
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "Server listener error: " + e.getMessage(), e);
                }
            }
        });
    }

    public synchronized void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
    }

    private void handleClient(Socket socket) {
        try (InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase();
            String uri = parts[1];

            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String headerName = line.substring(0, colon).trim().toLowerCase();
                    String headerVal = line.substring(colon + 1).trim();
                    if (headerName.equals("content-length")) {
                        try {
                            contentLength = Integer.parseInt(headerVal);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            String body = "";
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                int totalRead = 0;
                while (totalRead < contentLength) {
                    int r = reader.read(bodyChars, totalRead, contentLength - totalRead);
                    if (r == -1) break;
                    totalRead += r;
                }
                body = new String(bodyChars, 0, totalRead);
            }

            // Handle CORS preflight
            if (method.equals("OPTIONS")) {
                sendResponse(out, 200, "OK", "application/json", "{}");
                return;
            }

            String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
            JSONObject responseJson = dispatch(method, path, body);
            sendResponse(out, 200, "OK", "application/json", responseJson.toString());

        } catch (Exception e) {
            Log.e(TAG, "Request handling failed: " + e.getMessage(), e);
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private JSONObject dispatch(String method, String path, String body) {
        JSONObject res = new JSONObject();
        try {
            JSONObject reqBody = null;
            if (body != null && !body.trim().isEmpty()) {
                try {
                    reqBody = new JSONObject(body);
                } catch (Exception ignored) {}
            }

            switch (path) {
                case "/health":
                    res.put("status", "ok");
                    res.put("service", "ai.sol.hermes");
                    res.put("port", port);
                    break;

                case "/tools":
                    JSONArray tools = new JSONArray();
                    tools.put(createTool("health", "Check device capability service health"));
                    tools.put(createTool("device-info", "Get Android device model, SDK and manufacturer"));
                    tools.put(createTool("notify", "Trigger a system notification with title and message"));
                    tools.put(createTool("toast", "Display a brief toast message on screen"));
                    tools.put(createTool("alarm", "Schedule a reminder notification with delay_seconds"));
                    tools.put(createTool("clipboard-set", "Write text to Android clipboard"));
                    tools.put(createTool("clipboard-get", "Read current text from Android clipboard"));
                    tools.put(createTool("open-url", "Open a web URL in the default browser"));
                    res.put("tools", tools);
                    break;

                case "/device-info":
                    res.put("model", Build.MODEL);
                    res.put("manufacturer", Build.MANUFACTURER);
                    res.put("brand", Build.BRAND);
                    res.put("device", Build.DEVICE);
                    res.put("product", Build.PRODUCT);
                    res.put("sdk", Build.VERSION.SDK_INT);
                    res.put("release", Build.VERSION.RELEASE);
                    res.put("package", context.getPackageName());
                    break;

                case "/notify":
                    if (reqBody != null) {
                        String title = reqBody.optString("title", "Hermes Agent");
                        String message = reqBody.optString("message", "");
                        triggerNotification(title, message);
                        res.put("status", "ok");
                        res.put("delivered", true);
                    } else {
                        res.put("status", "error");
                        res.put("error", "Missing request body");
                    }
                    break;

                case "/toast":
                    if (reqBody != null) {
                        String message = reqBody.optString("message", "");
                        mainHandler.post(() -> {
                            try {
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                            } catch (Exception ignored) {}
                        });
                        res.put("status", "ok");
                    } else {
                        res.put("status", "error");
                        res.put("error", "Missing message");
                    }
                    break;

                case "/alarm":
                    if (reqBody != null) {
                        int delaySeconds = reqBody.optInt("delay_seconds", 60);
                        String title = reqBody.optString("title", "Hermes Agent Reminder");
                        String message = reqBody.optString("message", "Scheduled reminder");
                        mainHandler.postDelayed(() -> triggerNotification(title, message), delaySeconds * 1000L);
                        res.put("status", "ok");
                        res.put("scheduled_seconds", delaySeconds);
                    }
                    break;

                case "/clipboard":
                    if (method.equals("POST")) {
                        if (reqBody != null) {
                            String text = reqBody.optString("text", "");
                            mainHandler.post(() -> {
                                try {
                                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                                    if (cm != null) {
                                        cm.setPrimaryClip(ClipData.newPlainText("Hermes Agent", text));
                                    }
                                } catch (Exception e) {
                                    Log.w(TAG, "Clipboard write error: " + e.getMessage());
                                }
                            });
                            res.put("status", "ok");
                        }
                    } else {
                        // GET clipboard with protection against Android 10+ background restrictions
                        try {
                            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                            String text = "";
                            if (cm != null && cm.hasPrimaryClip()) {
                                ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
                                if (item != null && item.getText() != null) {
                                    text = item.getText().toString();
                                }
                            }
                            res.put("text", text);
                        } catch (SecurityException se) {
                            Log.w(TAG, "Clipboard read restricted by Android security: " + se.getMessage());
                            res.put("text", "");
                            res.put("warning", "Clipboard access requires app focus on Android 10+");
                        } catch (Exception e) {
                            res.put("text", "");
                        }
                    }
                    break;

                case "/open-url":
                    if (reqBody != null) {
                        String url = reqBody.optString("url", "");
                        if (!url.isEmpty()) {
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                context.startActivity(intent);
                                res.put("status", "ok");
                            } catch (Exception e) {
                                res.put("status", "error");
                                res.put("error", e.getMessage());
                            }
                        }
                    }
                    break;

                case "/diagnostics":
                    res.put("status", "ok");
                    res.put("timestamp", System.currentTimeMillis());
                    res.put("available_processors", Runtime.getRuntime().availableProcessors());
                    res.put("free_memory_mb", Runtime.getRuntime().freeMemory() / (1024 * 1024));
                    res.put("total_memory_mb", Runtime.getRuntime().totalMemory() / (1024 * 1024));
                    break;

                default:
                    res.put("status", "ok");
                    res.put("message", "Endpoint accepted by Hermes capability bridge");
                    break;
            }
        } catch (Exception e) {
            try {
                res.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return res;
    }

    private JSONObject createTool(String name, String desc) throws Exception {
        JSONObject t = new JSONObject();
        t.put("name", name);
        t.put("description", desc);
        return t;
    }

    private void triggerNotification(String title, String message) {
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) return;

            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setAutoCancel(true);

            manager.notify((int) System.currentTimeMillis(), builder.build());
        } catch (SecurityException se) {
            Log.w(TAG, "Notification permission denied (POST_NOTIFICATIONS): " + se.getMessage());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to trigger notification: " + t.getMessage());
        }
    }

    private void sendResponse(OutputStream out, int code, String msg, String contentType, String body) throws Exception {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + code + " " + msg + "\r\n" +
                "Content-Type: " + contentType + "; charset=utf-8\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bodyBytes);
        out.flush();
    }
}
