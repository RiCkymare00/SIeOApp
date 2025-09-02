package com.example.sio;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private WebView webView;
    private View splashContainer;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Usa il layout XML che contiene WebView + splash overlay
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.myWeb);
        splashContainer = findViewById(R.id.splash_container);

        // Mostra splash all'avvio (visibile di default se nell'XML è VISIBLE)
        splashContainer.setVisibility(View.VISIBLE);
        splashContainer.setAlpha(1f);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setDatabaseEnabled(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccess(true);

        // Enable WebView debugging in debug builds
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                Log.d(TAG, "Page started: " + url);
                // mostra la splash mentre carica
                runOnUiThread(() -> {
                    splashContainer.setAlpha(1f);
                    splashContainer.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d(TAG, "Page finished loading: " + url);
                // nascondi la splash con una semplice fade-out
                runOnUiThread(() -> {
                    splashContainer.animate()
                            .alpha(0f)
                            .setDuration(300)
                            .withEndAction(() -> splashContainer.setVisibility(View.GONE));
                });
            }
        });

        webView.addJavascriptInterface(new BlobDownloader(), "BlobHandler");

        // DownloadListener per download standard (opzionale)
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimeType,
                                        long contentLength) {
                Log.d(TAG, "onDownloadStart - URL: " + url);

                if (url != null && url.startsWith("blob:")) {
                    Log.w(TAG, "Blob URL detected in DownloadListener - should be handled by BlobHandler");
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    "Blob download - using BlobHandler instead",
                                    Toast.LENGTH_SHORT).show());
                } else if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    // Handle standard downloads if needed
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(browserIntent);
                }
            }
        });

        String loadUrl = "https://assistenza.sistemiweb.it:10501/";
        Log.d(TAG, "Loading initial URL: " + loadUrl);
        webView.loadUrl(loadUrl);
    }

    // ---------- BlobDownloader (mantengo la tua implementazione) ----------
    public class BlobDownloader {

        @JavascriptInterface
        public void receiveBlob(String base64DataUrl, String mimeType, String contentDisposition) {
            Log.d(TAG, "receiveBlob called by JavaScript");
            Log.d(TAG, "MimeType: " + mimeType);
            Log.d(TAG, "ContentDisposition: " + contentDisposition);

            if (base64DataUrl == null || !base64DataUrl.startsWith("data:") || !base64DataUrl.contains(",")) {
                Log.e(TAG, "Invalid base64 data URL: " + (base64DataUrl != null ? base64DataUrl.substring(0, Math.min(100, base64DataUrl.length())) : "null"));
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Error: Invalid data from web page", Toast.LENGTH_LONG).show());
                return;
            }

            try {
                // Extract base64 data
                String base64Data = base64DataUrl.substring(base64DataUrl.indexOf(",") + 1);
                byte[] fileBytes = Base64.decode(base64Data, Base64.DEFAULT);

                Log.d(TAG, "Successfully decoded " + fileBytes.length + " bytes");

                if (fileBytes.length == 0) {
                    Log.e(TAG, "Decoded file is empty");
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this, "Error: File is empty", Toast.LENGTH_LONG).show());
                    return;
                }

                // Extract filename
                String fileName = extractFileName(contentDisposition, mimeType);
                Log.d(TAG, "Using filename: " + fileName);

                // Save file using appropriate method based on Android version
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveFileWithMediaStore(fileBytes, fileName, mimeType);
                } else {
                    saveFileToDownloads(fileBytes, fileName);
                }

            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Base64 decoding error", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Error decoding file data", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Log.e(TAG, "Error in receiveBlob", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Error processing file: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        private String extractFileName(String contentDisposition, String mimeType) {
            String fileName = "downloaded_file";

            // Try to extract from Content-Disposition
            if (contentDisposition != null && !contentDisposition.isEmpty()) {
                String[] parts = contentDisposition.split(";");
                for (String part : parts) {
                    String trimmedPart = part.trim();
                    if (trimmedPart.toLowerCase().startsWith("filename=")) {
                        String extractedName = trimmedPart.substring(trimmedPart.indexOf('=') + 1).trim();
                        extractedName = extractedName.replaceAll("^\"|\"$", ""); // Remove quotes
                        if (!extractedName.isEmpty()) {
                            fileName = sanitizeFileName(extractedName);
                            return fileName;
                        }
                    }
                }
            }

            // Add extension based on mime type
            if (mimeType != null) {
                if (mimeType.equals("application/pdf")) {
                    fileName += ".pdf";
                } else if (mimeType.equals("text/plain")) {
                    fileName += ".txt";
                } else if (mimeType.startsWith("image/")) {
                    fileName += "." + mimeType.substring(6);
                }
            }

            return fileName;
        }

        private String sanitizeFileName(String fileName) {
            // Remove or replace invalid characters
            return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        }

        // For Android 10+ (API 29+) - Use MediaStore
        private void saveFileWithMediaStore(byte[] fileBytes, String fileName, String mimeType) {
            try {
                ContentResolver resolver = getContentResolver();
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType != null ? mimeType : "application/octet-stream");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                Uri uri = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
                }

                if (uri != null) {
                    try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                        if (outputStream != null) {
                            outputStream.write(fileBytes);
                            outputStream.flush();

                            Log.d(TAG, "File saved successfully with MediaStore: " + fileName);
                            runOnUiThread(() ->
                                    Toast.makeText(MainActivity.this,
                                            "File saved: " + fileName, Toast.LENGTH_LONG).show());
                        } else {
                            throw new Exception("Could not open output stream");
                        }
                    }
                } else {
                    throw new Exception("Could not create MediaStore entry");
                }

            } catch (Exception e) {
                Log.e(TAG, "Error saving with MediaStore", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                                "Error saving file: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        // For older Android versions - Save to Downloads folder directly
        private void saveFileToDownloads(byte[] fileBytes, String fileName) {
            try {
                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs();
                }

                File file = new File(downloadsDir, fileName);

                // If file exists, add number suffix
                int counter = 1;
                String baseName = fileName;
                String extension = "";
                int dotIndex = fileName.lastIndexOf('.');
                if (dotIndex > 0) {
                    baseName = fileName.substring(0, dotIndex);
                    extension = fileName.substring(dotIndex);
                }

                while (file.exists()) {
                    file = new File(downloadsDir, baseName + "_" + counter + extension);
                    counter++;
                }

                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(fileBytes);
                    fos.flush();

                    Log.d(TAG, "File saved successfully to Downloads: " + file.getAbsolutePath());
                    File finalFile = file;
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    "File saved to Downloads: " + finalFile.getName(), Toast.LENGTH_LONG).show());
                }

            } catch (Exception e) {
                Log.e(TAG, "Error saving to Downloads folder", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                                "Error saving file: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void onBlobError(String errorMessage) {
            Log.e(TAG, "JavaScript reported error: " + errorMessage);
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this,
                            "Web page error: " + errorMessage, Toast.LENGTH_LONG).show());
        }

        @JavascriptInterface
        public void logDebug(String message) {
            Log.d(TAG, "JS Debug: " + message);
        }
    }

    // ---------- fine BlobDownloader ----------

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("BlobHandler");
            } catch (Exception ignored) {}
            webView.setWebViewClient(null);
            webView.setDownloadListener(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
