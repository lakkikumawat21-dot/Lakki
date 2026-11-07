package ai.sol.hermes;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Automates downloading (with multi-mirror failover), unpacking (.7z & .tar.gz),
 * and configuring the internal PRoot subsystem.
 */
public class RootfsInstaller {
    private static final String TAG = "RootfsInstaller";

    public static class Profile {
        public final String id;
        public final String name;
        public final String description;
        public final String downloadUrl;
        public final String approxSize;
        public final boolean is7z;

        public Profile(String id, String name, String description, String downloadUrl, String approxSize, boolean is7z) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.downloadUrl = downloadUrl;
            this.approxSize = approxSize;
            this.is7z = is7z;
        }
    }

    public static final Profile PROFILE_ARCH_UBUNTU_7Z = new Profile(
            "arch_ubuntu_7z",
            "Hermes Standard (Ubuntu arch-ubuntu.7z)",
            "Official standalone Xermes release with Python 3.14 & Node runtime",
            UbuntuManager.PRIMARY_RELEASE_URL,
            "~22 MB download (~85 MB extracted)",
            true
    );

    public static final Profile PROFILE_UBUNTU_NOBLE_TAR = new Profile(
            "ubuntu_noble",
            "Hermes Official Ubuntu 24.04 Noble",
            "Direct Ubuntu 24.04 LTS (Noble Numbat) official cloud rootfs",
            UbuntuManager.UBUNTU_CLOUD_URL,
            "~28.5 MB download (~85 MB extracted)",
            false
    );

    public static final Profile PROFILE_ALPINE_LITE = new Profile(
            "alpine_lite",
            "Hermes Lite (Alpine Linux 3.20)",
            "Ultra-compact lightweight base rootfs for minimal storage footprint",
            "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
            "~3.9 MB download (~12 MB extracted)",
            false
    );

    public interface InstallCallback {
        void onProgress(int percent, String stepTitle, String details);
        void onLog(String logMessage);
        void onSuccess();
        void onError(String message, Throwable error);
    }

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean isCancelled = false;

    public RootfsInstaller(Context context) {
        this.context = context.getApplicationContext();
    }

    public void startInstall(Profile profile, InstallCallback callback) {
        isCancelled = false;
        executor.execute(() -> {
            try {
                notifyLog(callback, "=== Starting Hermes Agent Subsystem Setup ===");
                notifyLog(callback, "Target Profile: " + profile.name);
                notifyLog(callback, "Base URL: " + profile.downloadUrl);

                File filesDir = context.getFilesDir();
                File rootfsDir = InstallationManager.getRootfsDir(context);
                File workspaceDir = InstallationManager.getWorkspaceDir(context);

                String archiveName = profile.is7z ? "rootfs_download.7z" : "rootfs_download.tar.gz";
                File archiveFile = new File(filesDir, archiveName);

                // Step 1: Ensure native binaries are present
                notifyProgress(callback, 5, "Preparing Native Binaries", "Verifying libproot.so and libbusybox.so...");
                String prootPath = InstallationManager.findOrExtractProotBinary(context);
                notifyLog(callback, "[+] PRoot binary ready at: " + prootPath);

                // Step 2: Download with mirror failover
                notifyProgress(callback, 10, "Downloading RootFS", "Connecting to primary/mirror CDNs (" + profile.approxSize + ")...");
                downloadWithFailover(profile.downloadUrl, archiveFile, callback);

                if (isCancelled) return;
                notifyLog(callback, "[+] Download complete: " + (archiveFile.length() / (1024 * 1024)) + " MB");

                // Step 3: Extract archive (.7z or .tar.gz)
                notifyProgress(callback, 65, "Extracting Linux Filesystem", "Restoring system tree and symbolic links...");
                notifyLog(callback, "[*] Extracting " + archiveFile.getName() + " into " + rootfsDir.getAbsolutePath() + "...");

                int count = ArchiveExtractor.extract(archiveFile, rootfsDir, (extractedCount, currentEntry) -> {
                    if (extractedCount % 500 == 0) {
                        notifyLog(callback, "Extracting: " + currentEntry + " (#" + extractedCount + ")");
                    }
                });

                notifyLog(callback, "[+] Extracted " + count + " entries successfully.");
                archiveFile.delete(); // Free download space

                // Step 4: Network & Workspace configuration
                notifyProgress(callback, 85, "Configuring Network Subsystem", "Configuring resolv.conf, hosts, and workspace...");
                setupResolvConf(rootfsDir);
                setupHosts(rootfsDir);
                setupWorkspace(rootfsDir, workspaceDir);

                // Step 5: Inject Boot Scripts & Profile
                notifyProgress(callback, 92, "Installing Startup Chain", "Injecting hermes launcher and profile scripts...");
                injectBootFiles(rootfsDir, callback);

                // Step 6: Mark Installed & Link Persistence
                InstallationManager.markInstalled(context, profile.id);
                new HermesDataManager(context).ensurePersistenceLinked();

                notifyProgress(callback, 100, "Setup Complete", "Hermes Agent Linux subsystem ready!");
                notifyLog(callback, "=== Subsystem Setup Finished Successfully ===");

                mainHandler.post(callback::onSuccess);

            } catch (Throwable t) {
                Log.e(TAG, "Installation failed: " + t.getMessage(), t);
                notifyLog(callback, "[-] ERROR: " + t.getMessage());
                mainHandler.post(() -> callback.onError(t.getMessage(), t));
            }
        });
    }

    private void downloadWithFailover(String primaryUrl, File destFile, InstallCallback callback) throws Exception {
        UbuntuManager ubuntuManager = UbuntuManager.getInstance(context);
        List<String> candidateUrls = ubuntuManager.getCandidateUrls(primaryUrl);
        if (candidateUrls.isEmpty()) {
            candidateUrls.add(primaryUrl);
        }

        Exception lastException = null;
        for (int i = 0; i < candidateUrls.size(); i++) {
            String candidate = candidateUrls.get(i);
            try {
                notifyLog(callback, "[*] Attempting download (" + (i + 1) + "/" + candidateUrls.size() + "): " + candidate);
                downloadFile(candidate, destFile, (percent, downloadedBytes, totalBytes) -> {
                    String mbInfo = String.format("%.1f MB / %.1f MB", downloadedBytes / (1024.0 * 1024.0), totalBytes / (1024.0 * 1024.0));
                    int overallPercent = 10 + (int) (percent * 0.50); // 10% to 60%
                    notifyProgress(callback, overallPercent, "Downloading RootFS", mbInfo + " (" + percent + "%)");
                }, callback);
                return; // Succeeded!
            } catch (Exception e) {
                lastException = e;
                notifyLog(callback, "[!] Mirror attempt failed: " + e.getMessage() + " - trying next mirror...");
                if (destFile.exists()) destFile.delete();
            }
        }

        throw (lastException != null) ? lastException : new Exception("All download mirrors failed for: " + primaryUrl);
    }

    private interface DownloadProgressListener {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
    }

    private void downloadFile(String urlStr, File destFile, DownloadProgressListener listener, InstallCallback callback) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "HermesAgent-Android/1.1");
        conn.connect();

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_MOVED_PERM || responseCode == HttpURLConnection.HTTP_MOVED_TEMP) {
            String newUrl = conn.getHeaderField("Location");
            conn.disconnect();
            downloadFile(newUrl, destFile, listener, callback);
            return;
        }

        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw new Exception("HTTP " + responseCode + " for " + urlStr);
        }

        long totalLength = conn.getContentLengthLong();
        notifyLog(callback, "[*] Connected. Content length: " + (totalLength / (1024 * 1024)) + " MB");

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(destFile)) {

            byte[] buffer = new byte[32768];
            long totalRead = 0;
            int bytesRead;
            long lastNotify = System.currentTimeMillis();

            while ((bytesRead = in.read(buffer)) != -1) {
                if (isCancelled) {
                    destFile.delete();
                    return;
                }
                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                long now = System.currentTimeMillis();
                if (now - lastNotify > 250) {
                    lastNotify = now;
                    int percent = totalLength > 0 ? (int) ((totalRead * 100) / totalLength) : 0;
                    listener.onProgress(percent, totalRead, totalLength);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private void setupResolvConf(File rootfsDir) {
        try {
            File etc = new File(rootfsDir, "etc");
            if (!etc.exists()) etc.mkdirs();
            File resolvConf = new File(etc, "resolv.conf");
            if (resolvConf.exists()) resolvConf.delete();
            try (FileOutputStream fos = new FileOutputStream(resolvConf)) {
                String conf = "nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 8.8.4.4\n";
                fos.write(conf.getBytes());
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to write resolv.conf: " + e.getMessage());
        }
    }

    private void setupHosts(File rootfsDir) {
        try {
            File hosts = new File(rootfsDir, "etc/hosts");
            if (!hosts.exists()) {
                try (FileOutputStream fos = new FileOutputStream(hosts)) {
                    String conf = "127.0.0.1 localhost\n127.0.0.2 localhost\n::1 localhost\n";
                    fos.write(conf.getBytes());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to write hosts: " + e.getMessage());
        }
    }

    private void setupWorkspace(File rootfsDir, File workspaceDir) {
        try {
            File rootDir = new File(rootfsDir, "root");
            if (!rootDir.exists()) rootDir.mkdirs();
            File internalWorkspace = new File(rootDir, "workspace");
            if (!internalWorkspace.exists()) internalWorkspace.mkdirs();
        } catch (Exception ignored) {}
    }

    private void injectBootFiles(File rootfsDir, InstallCallback callback) {
        try {
            AssetManager am = context.getAssets();
            File usrLocalBin = new File(rootfsDir, "usr/local/bin");
            if (!usrLocalBin.exists()) usrLocalBin.mkdirs();

            copyAssetFile(am, "boot_files/usr_local_bin/hermes", new File(usrLocalBin, "hermes"), true);
            copyAssetFile(am, "boot_files/usr_local_bin/hermes-agent", new File(usrLocalBin, "hermes-agent"), true);
            copyAssetFile(am, "boot_files/usr_local_bin/hermes-android-doctor", new File(usrLocalBin, "hermes-android-doctor"), true);

            File rootDir = new File(rootfsDir, "root");
            if (!rootDir.exists()) rootDir.mkdirs();
            copyAssetFile(am, "boot_files/profiles/.bashrc", new File(rootDir, ".bashrc"), false);
            copyAssetFile(am, "boot_files/profiles/.profile", new File(rootDir, ".profile"), false);

            notifyLog(callback, "[+] Injected Hermes launcher and profile scripts.");
        } catch (Exception e) {
            notifyLog(callback, "[!] Boot files notice: " + e.getMessage());
        }
    }

    private void copyAssetFile(AssetManager am, String assetPath, File destFile, boolean makeExecutable) {
        try (InputStream in = am.open(assetPath);
             FileOutputStream out = new FileOutputStream(destFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            if (makeExecutable) {
                InstallationManager.setExecutable(destFile);
            }
        } catch (Exception ignored) {}
    }

    private void notifyProgress(InstallCallback callback, int percent, String title, String details) {
        mainHandler.post(() -> callback.onProgress(percent, title, details));
    }

    private void notifyLog(InstallCallback callback, String msg) {
        Log.i(TAG, msg);
        mainHandler.post(() -> callback.onLog(msg));
    }
}
