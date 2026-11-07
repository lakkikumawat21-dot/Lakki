package ai.sol.hermes;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 100% Standalone Hermes Agent Android Subsystem & Dashboard.
 * Inbuilt QROOT/PRoot Linux installer, runtime manager, and WebView client.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "HermesMainActivity";

    public static final String PRIMARY_URL = "http://127.0.0.2:39067";
    public static final String FALLBACK_URL = "http://127.0.0.1:39067";
    private static final int PERMISSION_REQUEST_NOTIFICATIONS = 101;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;

    // UI Layers
    private ScrollView installerLayout;
    private LinearLayout loadingLayout;
    private SwipeRefreshLayout swipeRefresh;
    private WebView webView;

    // Installer Elements
    private RadioGroup rgProfiles;
    private RadioButton rbUbuntuNoble;
    private RadioButton rbAlpineLite;
    private TextView txtDetectedArch;
    private LinearLayout progressContainer;
    private ProgressBar installProgressBar;
    private TextView txtInstallStepTitle;
    private TextView txtInstallStepDetails;
    private ScrollView consoleScroll;
    private TextView txtConsoleLog;
    private Button btnStartInstall;

    // Boot / Loading Elements
    private TextView txtStatus;
    private TextView txtSubStatus;
    private Button btnRetry;
    private Button btnViewLogs;
    private Button btnResetSubsystem;

    private String targetUrl = PRIMARY_URL;
    private boolean isEngineReady = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService pollExecutor = Executors.newSingleThreadExecutor();
    private ValueCallback<Uri[]> fileUploadCallback;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Notification permission check (Android 13+)
        checkNotificationPermission();

        // 2. Bind views
        installerLayout = findViewById(R.id.installerLayout);
        loadingLayout = findViewById(R.id.loadingLayout);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        webView = findViewById(R.id.webView);

        rgProfiles = findViewById(R.id.rgProfiles);
        rbUbuntuNoble = findViewById(R.id.rbUbuntuNoble);
        rbAlpineLite = findViewById(R.id.rbAlpineLite);
        txtDetectedArch = findViewById(R.id.txtDetectedArch);
        progressContainer = findViewById(R.id.progressContainer);
        installProgressBar = findViewById(R.id.installProgressBar);
        txtInstallStepTitle = findViewById(R.id.txtInstallStepTitle);
        txtInstallStepDetails = findViewById(R.id.txtInstallStepDetails);
        consoleScroll = findViewById(R.id.consoleScroll);
        txtConsoleLog = findViewById(R.id.txtConsoleLog);
        btnStartInstall = findViewById(R.id.btnStartInstall);

        txtStatus = findViewById(R.id.txtStatus);
        txtSubStatus = findViewById(R.id.txtSubStatus);
        btnRetry = findViewById(R.id.btnRetry);
        btnViewLogs = findViewById(R.id.btnViewLogs);
        btnResetSubsystem = findViewById(R.id.btnResetSubsystem);

        configureWebView();

        swipeRefresh.setColorSchemeResources(R.color.teal_hermes);
        swipeRefresh.setOnRefreshListener(() -> {
            if (webView != null) webView.reload();
        });

        // 3. Setup back press handler
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.getVisibility() == View.VISIBLE && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        // 4. Button Listeners
        btnStartInstall.setOnClickListener(v -> initiateInstallation());
        btnRetry.setOnClickListener(v -> checkEngineAndConnect());
        btnViewLogs.setOnClickListener(v -> showLogsDialog());
        btnResetSubsystem.setOnClickListener(v -> promptResetSubsystem());

        // 5. Check Installation State
        checkInitialState();
    }

    private void checkInitialState() {
        if (!InstallationManager.isInstalled(this)) {
            showInstallerUI();
        } else {
            startSubsystemAndLoadDashboard();
        }
    }

    private void showInstallerUI() {
        installerLayout.setVisibility(View.VISIBLE);
        loadingLayout.setVisibility(View.GONE);
        swipeRefresh.setVisibility(View.GONE);

        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "arm64-v8a";
        txtDetectedArch.setText("Target Device: " + abi + " | Inbuilt PRoot Engine (libproot.so)");
    }

    private void initiateInstallation() {
        btnStartInstall.setEnabled(false);
        rbUbuntuNoble.setEnabled(false);
        rbAlpineLite.setEnabled(false);
        progressContainer.setVisibility(View.VISIBLE);

        RootfsInstaller.Profile profile = rbAlpineLite.isChecked()
                ? RootfsInstaller.PROFILE_ALPINE_LITE
                : RootfsInstaller.PROFILE_UBUNTU_NOBLE;

        RootfsInstaller installer = new RootfsInstaller(this);
        installer.startInstall(profile, new RootfsInstaller.InstallCallback() {
            @Override
            public void onProgress(int percent, String stepTitle, String details) {
                installProgressBar.setProgress(percent);
                txtInstallStepTitle.setText(stepTitle);
                txtInstallStepDetails.setText(details);
            }

            @Override
            public void onLog(String logMessage) {
                txtConsoleLog.append(logMessage + "\n");
                consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
            }

            @Override
            public void onSuccess() {
                Toast.makeText(MainActivity.this, "Installation complete! Booting engine...", Toast.LENGTH_SHORT).show();
                startSubsystemAndLoadDashboard();
            }

            @Override
            public void onError(String message, Throwable error) {
                btnStartInstall.setEnabled(true);
                rbUbuntuNoble.setEnabled(true);
                rbAlpineLite.setEnabled(true);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Setup Error")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show();
            }
        });
    }

    private void startSubsystemAndLoadDashboard() {
        installerLayout.setVisibility(View.GONE);
        loadingLayout.setVisibility(View.VISIBLE);
        swipeRefresh.setVisibility(View.GONE);

        txtStatus.setText("Starting PRoot Linux Subsystem...");
        txtSubStatus.setText("Launching Hermes Agent engine & dashboard (127.0.0.2:39067)");

        // Start Foreground Service (which runs PRootRunner and CapabilityServer)
        startEngineService();

        // Start polling for dashboard HTTP readiness
        checkEngineAndConnect();
    }

    private void startEngineService() {
        try {
            Intent serviceIntent = new Intent(this, HermesService.class);
            serviceIntent.setAction("ACTION_START_ENGINE");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Service start warning: " + t.getMessage(), t);
            // Fallback: Start in-process
            PRootRunner.getInstance(this).start();
        }
    }

    private void checkEngineAndConnect() {
        txtStatus.setText("Connecting to Hermes Engine...");
        txtSubStatus.setText("Checking dashboard on " + targetUrl + "...");

        pollExecutor.execute(() -> {
            boolean reachable = false;
            String checkUrl = targetUrl + "/health";

            for (int attempt = 1; attempt <= 30; attempt++) {
                if (isFinishing() || (Build.VERSION.SDK_INT >= 17 && isDestroyed())) return;

                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(checkUrl).openConnection();
                    conn.setConnectTimeout(1500);
                    conn.setReadTimeout(1500);
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    if (code == 200 || code == 302 || code == 301) {
                        reachable = true;
                        break;
                    }
                } catch (Exception ignored) {}

                // Switch fallback between 127.0.0.2 and 127.0.0.1 on check
                checkUrl = (targetUrl.equals(PRIMARY_URL) ? FALLBACK_URL : PRIMARY_URL) + "/health";

                final int currentAttempt = attempt;
                mainHandler.post(() -> {
                    if (loadingLayout.getVisibility() == View.VISIBLE) {
                        txtSubStatus.setText("Engine boot in progress (Attempt " + currentAttempt + "/30)...");
                    }
                });

                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {}
            }

            final boolean isReady = reachable;
            mainHandler.post(() -> {
                if (isReady) {
                    showDashboard();
                } else {
                    txtStatus.setText("Hermes Agent Engine Initializing");
                    txtSubStatus.setText("Subsystem is warming up on " + targetUrl + ".\nClick Retry or check Engine Logs.");
                }
            });
        });
    }

    private void showDashboard() {
        isEngineReady = true;
        loadingLayout.setVisibility(View.GONE);
        installerLayout.setVisibility(View.GONE);
        swipeRefresh.setVisibility(View.VISIBLE);
        webView.loadUrl(targetUrl);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefresh.setRefreshing(false);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame() && !isEngineReady) {
                    loadingLayout.setVisibility(View.VISIBLE);
                    swipeRefresh.setVisibility(View.GONE);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                return super.onConsoleMessage(consoleMessage);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                try {
                    Intent intent = fileChooserParams.createIntent();
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                    return true;
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
            }
        });
    }

    private void showLogsDialog() {
        String logs = InstallationManager.readRecentLogs(this, 150);
        new AlertDialog.Builder(this)
                .setTitle("Hermes Engine Logs")
                .setMessage(logs)
                .setPositiveButton("Close", null)
                .setNeutralButton("Refresh", (dialog, which) -> showLogsDialog())
                .show();
    }

    private void promptResetSubsystem() {
        new AlertDialog.Builder(this)
                .setTitle("Reset Linux Subsystem?")
                .setMessage("This will delete the current rootfs environment and allow you to select a fresh profile. Workspace files will remain intact.")
                .setPositiveButton("Reset", (dialog, which) -> {
                    PRootRunner.getInstance(this).stop();
                    InstallationManager.resetEnvironment(this);
                    showInstallerUI();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_NOTIFICATIONS);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (fileUploadCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    } else if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webView != null) {
            webView.destroy();
        }
    }
}
