package ai.sol.hermes;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages RootFS mirrors, state verification, and process lifecycle.
 * Reconstructed from com.xermes.qroot.UbuntuManager.
 */
public class UbuntuManager {
    private static final String TAG = "UbuntuManager";

    public static final String PRIMARY_RELEASE_URL = "https://github.com/goldenduo/XermesRelease/releases/latest/download/arch-ubuntu.7z";
    public static final String UBUNTU_CLOUD_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.3-base-arm64.tar.gz";

    public static final List<String> MIRROR_PROXIES = Arrays.asList(
            "", // Direct
            "https://mirror.ghproxy.com/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
            "https://share.126111.xyz/",
            "https://tshare.126111.xyz/",
            "https://cdn.jsdelivr.net/gh/",
            "https://fastly.jsdelivr.net/gh/"
    );

    private static UbuntuManager instance;
    private final Context context;
    private PRootRunner prootRunner;

    public static synchronized UbuntuManager getInstance(Context context) {
        if (instance == null) {
            instance = new UbuntuManager(context);
        }
        return instance;
    }

    private UbuntuManager(Context context) {
        this.context = context.getApplicationContext();
        this.prootRunner = PRootRunner.getInstance(context);
    }

    public boolean isRootfsReady() {
        File rootfsDir = InstallationManager.getRootfsDir(context);
        File bash = new File(rootfsDir, "bin/bash");
        File sh = new File(rootfsDir, "bin/sh");
        boolean ready = rootfsDir.exists() && (bash.exists() || sh.exists());
        Log.d(TAG, ready ? "rootfs backend ready" : "rootfs backend not found");
        return ready;
    }

    public synchronized boolean startBackend() {
        Log.i(TAG, "Starting backend task...");
        if (!isRootfsReady()) {
            Log.w(TAG, "Cannot start backend: rootfs backend not found");
            return false;
        }

        if (prootRunner.isAlive()) {
            Log.i(TAG, "killing existing task");
            prootRunner.stop();
        }

        boolean started = prootRunner.start();
        if (started) {
            Log.i(TAG, "task backend started successfully");
        } else {
            Log.e(TAG, "Failed to start task backend");
        }
        return started;
    }

    public synchronized void stopBackend() {
        Log.i(TAG, "stopping backend task");
        if (prootRunner != null) {
            prootRunner.stop();
        }
    }

    public boolean isBackendRunning() {
        return prootRunner != null && prootRunner.isAlive();
    }

    public List<String> getCandidateUrls(String baseDownloadUrl) {
        List<String> urls = new ArrayList<>();
        for (String proxy : MIRROR_PROXIES) {
            if (proxy.isEmpty()) {
                urls.add(baseDownloadUrl);
            } else if (baseDownloadUrl.startsWith("https://github.com/")) {
                urls.add(proxy + baseDownloadUrl);
            }
        }
        return urls;
    }
}
