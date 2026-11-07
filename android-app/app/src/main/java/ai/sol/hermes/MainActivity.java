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
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * 100% Ad-Free, Open-Source Android WebView wrapper for Hermes Agent.
 * Bridges http://127.0.0.2:39067 dashboard and port 43097 Android capabilities.
 * Fully compatible with Android 13/14 permissions, themes, and lifecycle.
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "HermesMainActivity";

    public static final String PRIMARY_URL = "http://127.0.0.2:39067";
    public static final String FALLBACK_URL = "http://127.0.0.1:39067";
    private static final int PERMISSION_REQUEST_NOTIFICATIONS = 101;
    private static final int FILE_CHOOSER_REQUEST_CODE = 1001;

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout loadingLayout;
    private TextView txtStatus;
    private Button btnRetry;

    private String targetUrl = PRIMARY_URL;
    private boolean isLoaded = false;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> fileUploadCallback;
    private CapabilityServer inProcessCapabilityServer;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. Request POST_NOTIFICATIONS on Android 13+ (API 33)
        checkNotificationPermission();

        // 2. Start background capability REST service safely on port 43097
        startCapabilityServiceSafely();

        webView = findViewById(R.id.webView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        loadingLayout = findViewById(R.id.loadingLayout);
        txtStatus = findViewById(R.id.txtStatus);
        btnRetry = findViewById(R.id.btnRetry);

        configureWebView();

        swipeRefresh.setColorSchemeResources(R.color.teal_hermes);
        swipeRefresh.setOnRefreshListener(() -> {
            if (webView != null) {
                webView.reload();
            }
        });

        btnRetry.setOnClickListener(v -> loadTargetUrl());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView != null && webView.canGoBack()) {
                    webView.goBack();
                } else {
                    finish();
                }
            }
        });

        loadTargetUrl();
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, PERMISSION_REQUEST_NOTIFICATIONS);
            }
        }
    }

    private void startCapabilityServiceSafely() {
        try {
            Intent serviceIntent = new Intent(this, HermesService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            Log.i(TAG, "HermesService started via startForegroundService.");
        } catch (Throwable t) {
            Log.w(TAG, "ForegroundService start restricted by OS (" + t.getMessage() + "), starting in-process CapabilityServer fallback.", t);
            if (inProcessCapabilityServer == null) {
                inProcessCapabilityServer = new CapabilityServer(this, CapabilityServer.DEFAULT_PORT);
                inProcessCapabilityServer.start();
            }
        }
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
                if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
                    return;
                }
                swipeRefresh.setRefreshing(false);
                isLoaded = true;
                loadingLayout.setVisibility(View.GONE);
                webView.setVisibility(View.VISIBLE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame()) {
                    handleConnectionError();
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                handleConnectionError();
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

    private void loadTargetUrl() {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }
        loadingLayout.setVisibility(View.VISIBLE);
        txtStatus.setText("Connecting to Hermes Engine (" + targetUrl + ")...");
        webView.loadUrl(targetUrl);
    }

    private void handleConnectionError() {
        if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
            return;
        }
        isLoaded = false;
        loadingLayout.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);

        // Switch fallback between 127.0.0.2 and 127.0.0.1
        if (targetUrl.equals(PRIMARY_URL)) {
            targetUrl = FALLBACK_URL;
        } else {
            targetUrl = PRIMARY_URL;
        }

        txtStatus.setText("Waiting for Hermes Agent backend on " + targetUrl + "...\nRetrying automatically in 3 seconds.");

        retryHandler.removeCallbacksAndMessages(null);
        retryHandler.postDelayed(() -> {
            if (!isFinishing() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed())) {
                loadTargetUrl();
            }
        }, 3000);
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_NOTIFICATIONS) {
            Log.i(TAG, "Notification permission result received.");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        retryHandler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.destroy();
        }
        if (inProcessCapabilityServer != null) {
            inProcessCapabilityServer.stop();
        }
    }
}
