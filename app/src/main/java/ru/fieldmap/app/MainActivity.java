package ru.fieldmap.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri imgUri, vidUri;
    private static final int REQ_CHOOSER = 42, REQ_CREATE = 43, REQ_NOTIF = 99;
    private byte[] pendingSaveBytes = null;
    private String pendingSaveName = "";
    private String pendingOpenNode = null;
    private final List<PendingDoc> docs = new ArrayList<>();
    private static class PendingDoc { String attId; String fileName; long mtime; }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        createChannel();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);
        s.setUseWideViewPort(true);

        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(0xFFF3F1EA);
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setDownloadListener((url, ua, cd, mime, len) -> { });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                if (params != null && params.isCaptureEnabled()) launchCamera();
                else launchDocs();
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) {
                injectBridge();
                if (pendingOpenNode != null) {
                    final String nid = pendingOpenNode; pendingOpenNode = null;
                    webView.postDelayed(() ->
                        webView.evaluateJavascript("if(window.__apkOpenNode)window.__apkOpenNode('" + nid + "')", null), 700);
                }
            }
        });

        if (getIntent() != null) {
            String nid = getIntent().getStringExtra("nodeId");
            if (nid != null && !nid.isEmpty()) pendingOpenNode = nid;
        }

        loadDocs();
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String nid = intent.getStringExtra("nodeId");
        if (nid != null && !nid.isEmpty() && webView != null) {
            webView.evaluateJavascript("if(window.__apkOpenNode)window.__apkOpenNode('" + nid + "')", null);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(Reminders.CHANNEL_ID,
                        "Напоминания", NotificationManager.IMPORTANCE_HIGH);
                ch.enableVibration(true);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    /* ---------- документы и файлы ---------- */
    private void launchDocs() {
        Intent docsPick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        docsPick.addCategory(Intent.CATEGORY_OPENABLE);
        docsPick.setType("*/*");
        docsPick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try { startActivityForResult(docsPick, REQ_CHOOSER); }
        catch (ActivityNotFoundException e) {
            toast("На телефоне нет файлового менеджера");
            filePathCallback = null;
        }
    }

    /* ---------- камера ---------- */
    private void launchCamera() {
        File img = new File(getCacheDir(), "fm_photo.jpg"); img.delete();
        File vid = new File(getCacheDir(), "fm_video.mp4"); vid.delete();
        imgUri = AssetFileProvider.uriFor(this, img.getName());
        vidUri = AssetFileProvider.uriFor(this, vid.getName());

        Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        cam.putExtra(MediaStore.EXTRA_OUTPUT, imgUri);
        cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent camv = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
        camv.putExtra(MediaStore.EXTRA_OUTPUT, vidUri);
        camv.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

        Intent chooser = Intent.createChooser(cam, "Камера");
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camv});
        try { startActivityForResult(chooser, REQ_CHOOSER); }
        catch (ActivityNotFoundException e) {
            toast("Нет приложения камеры");
            filePathCallback = null;
        }
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);

        if (req == REQ_CREATE) {
            if (res == RESULT_OK && data != null && data.getData() != null && pendingSaveBytes != null) {
                try {
                    OutputStream os = getContentResolver().openOutputStream(data.getData());
                    os.write(pendingSaveBytes); os.close();
                    toast("Сохранено: " + pendingSaveName);
                } catch (Exception e) { toast("Не удалось сохранить файл"); }
            } else {
                toast("Сохранение отменено");
            }
            pendingSaveBytes = null;
            return;
        }

        if (req != REQ_CHOOSER || filePathCallback == null) return;
        Uri[] out = null;
        if (res == RESULT_OK) {
            out = WebChromeClient.FileChooserParams.parseResult(res, data);
            if (out == null || out.length == 0) {
                if (new File(getCacheDir(), "fm_photo.jpg").length() > 0) out = new Uri[]{imgUri};
                else if (new File(getCacheDir(), "fm_video.mp4").length() > 0) out = new Uri[]{vidUri};
            }
        }
        if (res == RESULT_OK && (out == null || out.length == 0)) {
            toast("Файлы не получены из менеджера");
        }
        filePathCallback.onReceiveValue(out);
        filePathCallback = null;
    }

    /* ---------- открытые документы ---------- */
    private void loadDocs() {
        try {
            String j = getSharedPreferences("fieldmap", 0).getString("docs", "[]");
            JSONArray arr = new JSONArray(j);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                PendingDoc d = new PendingDoc();
                d.attId = o.getString("attId");
                d.fileName = o.getString("fileName");
                d.mtime = o.getLong("mtime");
                docs.add(d);
            }
        } catch (Exception ignored) {}
    }
    private void saveDocs() {
        try {
            JSONArray arr = new JSONArray();
            for (PendingDoc d : docs) {
                JSONObject o = new JSONObject();
                o.put("attId", d.attId); o.put("fileName", d.fileName); o.put("mtime", d.mtime);
                arr.put(o);
            }
            getSharedPreferences("fieldmap", 0).edit().putString("docs", arr.toString()).apply();
        } catch (Exception ignored) {}
    }
    @Override protected void onResume() {
        super.onResume();
        checkDocs();
    }
    private void checkDocs() {
        boolean changed = false;
        for (PendingDoc d : docs) {
            File f = new File(getCacheDir(), d.fileName);
            if (f.exists() && f.lastModified() > d.mtime) {
                d.mtime = f.lastModified(); changed = true;
                try {
                    byte[] b = Files.readAllBytes(f.toPath());
                    String b64 = Base64.encodeToString(b, Base64.NO_WRAP);
                    webView.evaluateJavascript(
                        "if(window.__apkFileUpdated)window.__apkFileUpdated('" + d.attId + "','" + b64 + "')", null);
                } catch (Exception ignored) {}
            }
        }
        if (changed) saveDocs();
    }

    private void injectBridge() {
        webView.evaluateJavascript(
            "(function(){if(window.__apkInjected)return;window.__apkInjected=1;" +
            "document.addEventListener('click',function(e){" +
            "var a=e.target.closest?e.target.closest('a[download]'):null;" +
            "if(a&&/^blob:/.test(a.href)&&window.AndroidBridge&&AndroidBridge.saveFile){e.preventDefault();e.stopPropagation();" +
            "fetch(a.href).then(function(r){return r.blob()}).then(function(b){" +
            "var rd=new FileReader();rd.onload=function(){" +
            "var b64=(rd.result.split(',')[1])||'';" +
            "AndroidBridge.saveFile(a.download||'file.bin',b64,b.type||'application/octet-stream');" +
            "};rd.readAsDataURL(b);}).catch(function(){});}},true);})();", null);
    }

    private String sanitize(String s) {
        if (s == null) return "file";
        s = s.replaceAll("[^0-9a-zA-Z._\\-\\u0400-\\u04FF ]", "_").trim().replace(' ', '_');
        return s.length() == 0 ? "file" : s;
    }
    private void toast(final String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
    }

    class Bridge {
        @JavascriptInterface public void saveFile(final String name, String base64, final String mime) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u != null) {
                    OutputStream os = getContentResolver().openOutputStream(u);
                    os.write(bytes); os.close();
                    toast("Сохранено: Загрузки/" + name);
                }
            } catch (Exception e) { toast("Ошибка сохранения файла"); }
        }

        @JavascriptInterface public void saveAs(final String name, String base64, final String mime) {
            pendingSaveBytes = Base64.decode(base64, Base64.DEFAULT);
            pendingSaveName = name;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                i.putExtra(Intent.EXTRA_TITLE, name);
                try { startActivityForResult(i, REQ_CREATE); }
                catch (ActivityNotFoundException e) { fallbackDownload(); }
            });
        }
        private void fallbackDownload() {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, pendingSaveName);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u != null) {
                    OutputStream os = getContentResolver().openOutputStream(u);
                    os.write(pendingSaveBytes); os.close();
                    toast("Сохранено: Загрузки/" + pendingSaveName);
                }
            } catch (Exception e) { toast("Ошибка сохранения файла"); }
            pendingSaveBytes = null;
        }

        @JavascriptInterface public void openDoc(final String attId, final String name, final String mime, final String b64, final boolean edit) {
            runOnUiThread(() -> {
                try {
                    String safe = sanitize(attId) + "_" + sanitize(name);
                    File f = new File(getCacheDir(), safe);
                    FileOutputStream os = new FileOutputStream(f);
                    os.write(Base64.decode(b64, Base64.DEFAULT)); os.close();
                    Uri u = AssetFileProvider.uriFor(MainActivity.this, safe);

                    PendingDoc d = null;
                    for (PendingDoc p : docs) if (p.attId.equals(attId)) d = p;
                    if (d == null) { d = new PendingDoc(); d.attId = attId; docs.add(d); }
                    d.fileName = safe; d.mtime = f.lastModified(); saveDocs();

                    Intent i = new Intent(edit ? Intent.ACTION_EDIT : Intent.ACTION_VIEW);
                    i.setDataAndType(u, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    try { startActivity(i); }
                    catch (ActivityNotFoundException e) {
                        if (edit) {
                            try {
                                Intent v = new Intent(Intent.ACTION_VIEW);
                                v.setDataAndType(u, i.getType());
                                v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                                startActivity(v);
                            } catch (ActivityNotFoundException e2) { toast("Нет приложения для этого формата"); }
                        } else toast("Нет приложения для этого формата");
                    }
                } catch (Exception e) { toast("Не удалось открыть файл"); }
            });
        }

        /* ---------- напоминания ---------- */
        @JavascriptInterface public void scheduleReminder(final String id, final String title, final String text, final long when) {
            runOnUiThread(() -> {
                ensureNotificationPermission();
                Reminders.schedule(MainActivity.this, id, title, text, when);
            });
        }
        @JavascriptInterface public void cancelReminder(final String id) {
            runOnUiThread(() -> Reminders.cancel(MainActivity.this, id));
        }
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
