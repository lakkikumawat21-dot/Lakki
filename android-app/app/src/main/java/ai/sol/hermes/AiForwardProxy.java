package ai.sol.hermes;

import android.content.Context;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 100% Ad-Free, zero-bloat internal local forward proxy.
 * Reconstructed from com.xermes.localproxy.AiForwardProxy.
 */
public class AiForwardProxy {
    private static final String TAG = "AiForwardProxy";
    public static final int DEFAULT_PROXY_PORT = 39068;

    private static AiForwardProxy instance;
    private final Context context;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public static synchronized AiForwardProxy getInstance(Context context) {
        if (instance == null) {
            instance = new AiForwardProxy(context);
        }
        return instance;
    }

    private AiForwardProxy(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        executor.execute(() -> {
            try {
                InetAddress loopback = InetAddress.getByName("127.0.0.1");
                serverSocket = new ServerSocket(DEFAULT_PROXY_PORT, 50, loopback);
                Log.i(TAG, "AiForwardProxy listening on 127.0.0.1:" + DEFAULT_PROXY_PORT);

                while (isRunning && serverSocket != null && !serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    client.setSoTimeout(15000);
                    executor.execute(() -> handleProxyClient(client));
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.d(TAG, "Proxy listener notice: " + e.getMessage());
                }
            }
        });
    }

    private void handleProxyClient(Socket client) {
        try (InputStream clientIn = client.getInputStream();
             OutputStream clientOut = client.getOutputStream()) {

            // Simple loopback relay to dashboard if requested
            Socket forward = new Socket(InetAddress.getByName(HermesAgentPaths.DASHBOARD_HOST), HermesAgentPaths.DASHBOARD_PORT);
            forward.setSoTimeout(15000);

            InputStream fwdIn = forward.getInputStream();
            OutputStream fwdOut = forward.getOutputStream();

            executor.execute(() -> {
                try {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = clientIn.read(buf)) > 0) {
                        fwdOut.write(buf, 0, len);
                        fwdOut.flush();
                    }
                } catch (Exception ignored) {}
            });

            byte[] buf = new byte[8192];
            int len;
            while ((len = fwdIn.read(buf)) > 0) {
                clientOut.write(buf, 0, len);
                clientOut.flush();
            }

            forward.close();
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    public synchronized void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
    }
}
