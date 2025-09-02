package com.example.sio;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import androidx.appcompat.app.AlertDialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceResponse;
import android.net.http.SslError;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_WRITE_EXTERNAL = 1001;

    private WebView webView;
    private View splashContainer;

    // Pending file (used if we must request WRITE_EXTERNAL_STORAGE on older devices)
    private byte[] pendingFileBytes = null;
    private String pendingFileName = null;
    private String pendingMimeType = null;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Usa il layout XML che contiene WebView + splash overlay
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.myWeb);
        splashContainer = findViewById(R.id.splash_container);

        if (splashContainer != null) {
            splashContainer.setVisibility(View.VISIBLE);
            splashContainer.setAlpha(1f);
        }

        // Impostazioni WebView
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowContentAccess(true);
        webSettings.setAllowFileAccess(true);

        // Permetti risorse miste se necessario (utile se la pagina carica risorse http da https)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        // Abilita debug dei contenuti WebView su build debug
        if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
            Log.d(TAG, "WebView debugging enabled");
        }

        // Prova a forzare layer type hardware (se riscontri problemi, prova software)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // WebChromeClient per console messages e progress
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, "JS Console: " + consoleMessage.message() +
                        " -- source: " + consoleMessage.sourceId() +
                        " -- line: " + consoleMessage.lineNumber());
                return super.onConsoleMessage(consoleMessage);
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                Log.d(TAG, "WebView progress: " + newProgress + "%");
                super.onProgressChanged(view, newProgress);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                Log.d(TAG, "Page title: " + title);
                super.onReceivedTitle(view, title);
            }
        });

        // WebViewClient con gestione errori più robusta
        webView.setWebViewClient(new WebViewClient() {

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.d(TAG, "onPageStarted: " + url);
                if (splashContainer != null) {
                    splashContainer.setVisibility(View.VISIBLE);
                    splashContainer.setAlpha(1f);
                }
                super.onPageStarted(view, url, favicon);
            }

            // Vecchio callback per errori (API < 23)
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "onReceivedError (old API). code=" + errorCode + " desc=" + description + " url=" + failingUrl);
                showErrorPage("Errore caricamento pagina: " + description + " (" + errorCode + ")");
                super.onReceivedError(view, errorCode, description, failingUrl);
            }

            // Nuovo callback per errori (API >= 23)
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                // ignora richieste secondarie (es. immagini) per non sovrascrivere il contenuto principale
                if (request.isForMainFrame()) {
                    Log.e(TAG, "onReceivedError (new API). " + error.getDescription() + " -- " + request.getUrl());
                    showErrorPage("Errore caricamento pagina: " + error.getDescription());
                } else {
                    Log.w(TAG, "Subresource error: " + request.getUrl() + " - " + error.getDescription());
                }
                super.onReceivedError(view, request, error);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                if (request.isForMainFrame()) {
                    Log.e(TAG, "HTTP error: " + errorResponse.getStatusCode() + " " + errorResponse.getReasonPhrase()
                            + " url=" + request.getUrl());
                    showErrorPage("Errore HTTP: " + errorResponse.getStatusCode());
                } else {
                    Log.w(TAG, "HTTP error subresource: " + request.getUrl());
                }
                super.onReceivedHttpError(view, request, errorResponse);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "onPageFinished: " + url);
                if (splashContainer != null) {
                    splashContainer.animate().alpha(0f).setDuration(300)
                            .withEndAction(() -> splashContainer.setVisibility(View.GONE));
                }
                super.onPageFinished(view, url);
            }

            @Override
            public void onReceivedSslError(WebView view, final SslErrorHandler handler, SslError error) {
                // Mostra dialog di avviso al dev/tester e, se conferma, fai proceed()
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Errore certificato")
                        .setMessage("Certificato non trusted. Procedere solo per debug? (insicuro)")
                        .setPositiveButton("Procedi", (d, w) -> handler.proceed())
                        .setNegativeButton("Annulla", (d, w) -> handler.cancel())
                        .show();
            }

            // Forzare il caricamento delle URL nella WebView stessa
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Log.d(TAG, "shouldOverrideUrlLoading: " + request.getUrl());
                return false; // apri nella stessa WebView
            }
        });

        // JavaScript interface per ricevere blob
        webView.addJavascriptInterface(new BlobDownloader(), "BlobHandler");

        // Download listener (come prima)
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
                                    "Blob download - usando BlobHandler", Toast.LENGTH_SHORT).show());
                } else if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(browserIntent);
                }
            }
        });

        // Caricamento pagina iniziale
        String loadUrl = "https://assistenza.sistemiweb.it:10501/";
        Log.d(TAG, "Loading initial URL: " + loadUrl);

        // Se vuoi verificare rapidamente che la WebView possa renderizzare, prova a caricare una pagina locale di test:
        // webView.loadData("<html><body><h1>Test WebView</h1></body></html>", "text/html", "utf-8");
        webView.loadUrl(loadUrl);
    }

    // Mostra una semplice pagina di errore nella WebView per evitare la schermata bianca
    private void showErrorPage(String message) {
        try {
            String html = "<html><body style='font-family: sans-serif; padding:20px;'>" +
                    "<h2>Errore caricamento pagina</h2>" +
                    "<p>" + escapeForHtml(message) + "</p>" +
                    "<p>Controlla la connessione o il certificato del server.</p>" +
                    "</body></html>";
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
                } else {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "showErrorPage failed", e);
        }
    }

    private String escapeForHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------- BlobDownloader (mantengo e semplifico la tua implementazione) ----------
    public class BlobDownloader {

        @JavascriptInterface
        public void receiveBlob(String base64DataUrl, String mimeType, String contentDisposition) {
            Log.d(TAG, "receiveBlob called by JavaScript");
            Log.d(TAG, "MimeType: " + mimeType);
            Log.d(TAG, "ContentDisposition: " + contentDisposition);

            if (base64DataUrl == null || !base64DataUrl.startsWith("data:") || !base64DataUrl.contains(",")) {
                Log.e(TAG, "Invalid base64 data URL.");
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Errore: dati invalidi dalla pagina web", Toast.LENGTH_LONG).show());
                return;
            }

            try {
                String base64Data = base64DataUrl.substring(base64DataUrl.indexOf(",") + 1);
                byte[] fileBytes = Base64.decode(base64Data, Base64.DEFAULT);

                Log.d(TAG, "Successfully decoded " + fileBytes.length + " bytes");

                if (fileBytes.length == 0) {
                    Log.e(TAG, "Decoded file is empty");
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this, "Errore: file vuoto", Toast.LENGTH_LONG).show());
                    return;
                }

                String fileName = extractFileName(contentDisposition, mimeType);
                Log.d(TAG, "Using filename: " + fileName);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    saveFileWithMediaStore(fileBytes, fileName, mimeType);
                } else {
                    saveFileToDownloads(fileBytes, fileName, mimeType);
                }

            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Base64 decoding error", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Errore decodifica dati file", Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Log.e(TAG, "Error in receiveBlob", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Errore: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        private String extractFileName(String contentDisposition, String mimeType) {
            String fileName = "downloaded_file";
            if (contentDisposition != null && !contentDisposition.isEmpty()) {
                String[] parts = contentDisposition.split(";");
                for (String part : parts) {
                    String trimmedPart = part.trim();
                    if (trimmedPart.toLowerCase().startsWith("filename=")) {
                        String extractedName = trimmedPart.substring(trimmedPart.indexOf('=') + 1).trim();
                        extractedName = extractedName.replaceAll("^\"|\"$", "");
                        if (!extractedName.isEmpty()) {
                            return sanitizeFileName(extractedName);
                        }
                    }
                }
            }
            if (mimeType != null) {
                if (mimeType.equals("application/pdf")) {
                    fileName += ".pdf";
                } else if (mimeType.equals("text/plain")) {
                    fileName += ".txt";
                } else if (mimeType.startsWith("image/")) {
                    fileName += "." + mimeType.substring(6);
                } else {
                    fileName += ".bin";
                }
            } else {
                fileName += ".bin";
            }
            return sanitizeFileName(fileName);
        }

        private String sanitizeFileName(String fileName) {
            return fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
        }

        private void saveFileWithMediaStore(byte[] fileBytes, String fileName, String mimeType) {
            try {
                ContentResolver resolver = getContentResolver();
                ContentValues contentValues = new ContentValues();
                contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType != null ? mimeType : "application/octet-stream");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 1);
                }

                Uri uri = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
                }
                if (uri != null) {
                    try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                        if (outputStream != null) {
                            outputStream.write(fileBytes);
                            outputStream.flush();

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                ContentValues cv = new ContentValues();
                                cv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                                resolver.update(uri, cv, null, null);
                            }

                            Log.d(TAG, "File saved successfully with MediaStore: " + fileName);
                            runOnUiThread(() ->
                                    Toast.makeText(MainActivity.this,
                                            "File salvato: " + fileName, Toast.LENGTH_LONG).show());
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
                                "Errore salvataggio: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        private void saveFileToDownloads(byte[] fileBytes, String fileName, String mimeType) {
            try {
                if (ContextCompat.checkSelfPermission(MainActivity.this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {

                    Log.d(TAG, "WRITE_EXTERNAL_STORAGE not granted, requesting permission and storing pending file");
                    pendingFileBytes = fileBytes;
                    pendingFileName = fileName;
                    pendingMimeType = mimeType;

                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                            REQUEST_WRITE_EXTERNAL);

                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    "Permesso necessario per salvare il file. Concedilo e riprova.", Toast.LENGTH_LONG).show());
                    return;
                }

                File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadsDir.exists()) {
                    boolean created = downloadsDir.mkdirs();
                    Log.d(TAG, "Downloads dir created: " + created);
                }

                File file = new File(downloadsDir, fileName);

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
                                    "File salvato in Downloads: " + finalFile.getName(), Toast.LENGTH_LONG).show());
                }

            } catch (Exception e) {
                Log.e(TAG, "Error saving to Downloads folder", e);
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this,
                                "Errore salvataggio: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }

        @JavascriptInterface
        public void onBlobError(String errorMessage) {
            Log.e(TAG, "JavaScript reported error: " + errorMessage);
            runOnUiThread(() ->
                    Toast.makeText(MainActivity.this,
                            "Errore dalla pagina web: " + errorMessage, Toast.LENGTH_LONG).show());
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

    /**
     * Gestione del risultato della richiesta di permessi.
     * Se il permesso WRITE_EXTERNAL_STORAGE è stato concesso e abbiamo un file in sospeso,
     * lo salviamo ora.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_WRITE_EXTERNAL) {
            boolean granted = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                granted = true;
            }

            if (granted) {
                Log.d(TAG, "WRITE_EXTERNAL_STORAGE granted by user.");
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Permesso concesso. Salvataggio in corso...", Toast.LENGTH_SHORT).show());

                if (pendingFileBytes != null && pendingFileName != null) {
                    new BlobSaverTask().savePendingFile();
                }
            } else {
                Log.w(TAG, "WRITE_EXTERNAL_STORAGE denied by user.");
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Permesso negato. Impossibile salvare il file.", Toast.LENGTH_LONG).show());
                pendingFileBytes = null;
                pendingFileName = null;
                pendingMimeType = null;
            }
        }
    }

    private class BlobSaverTask {
        void savePendingFile() {
            if (pendingFileBytes == null || pendingFileName == null) return;

            byte[] bytes = pendingFileBytes;
            String name = pendingFileName;
            String mime = pendingMimeType;

            pendingFileBytes = null;
            pendingFileName = null;
            pendingMimeType = null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                new BlobDownloader().saveFileWithMediaStore(bytes, name, mime);
            } else {
                new BlobDownloader().saveFileToDownloads(bytes, name, mime);
            }
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
        pendingFileBytes = null;
        pendingFileName = null;
        pendingMimeType = null;

        super.onDestroy();
    }
}
