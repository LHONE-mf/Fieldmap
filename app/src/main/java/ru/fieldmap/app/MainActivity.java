package ru.fieldmap.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.*;
import android.widget.Toast;
import java.io.File;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri imgUri, vidUri;
    private static final int REQ_CHOOSER = 42;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);      // включает IndexedDB
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);           // зум у карты свой, щипковый
        s.setUseWideViewPort(true);

        webView.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        webView.setBackgroundColor(0xFFF3F1EA);
        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setDownloadListener((url, ua, cd, mime, len) -> { /* перехватывается мостом */ });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView wv, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                launchChooser();
                return true;
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView v, String url) { injectBridge(); }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    /** Фото-камера + видео-камера + выбор файла из системы */
    private void launchChooser() {
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

        Intent docs = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        docs.addCategory(Intent.CATEGORY_OPENABLE);
        docs.setType("*/*");
        docs.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        Intent chooser = Intent.createChooser(docs, "Выбрать файл");
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam, camv});
        startActivityForResult(chooser, REQ_CHOOSER);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CHOOSER || filePathCallback == null) return;
        Uri[] out = null;
        if (res == RESULT_OK) {
            out = WebChromeClient.FileChooserParams.parseResult(res, data);
            if (out == null || out.length == 0) { // снято на камеру
                if (new File(getCacheDir(), "fm_photo.jpg").length() > 0) out = new Uri[]{imgUri};
                else if (new File(getCacheDir(), "fm_video.mp4").length() > 0) out = new Uri[]{vidUri};
            }
        }
        filePathCallback.onReceiveValue(out);
        filePathCallback = null;
    }

    /** Перехватывает скачивания (экспорт .json, файлы из просмотрщика)
        и сохраняет их в системную папку «Загрузки» через нативный мост. */
    private void injectBridge() {
        webView.evaluateJavascript(
            "(function(){if(window.__apkInjected)return;window.__apkInjected=1;" +
            "document.addEventListener('click',function(e){" +
            "var a=e.target.closest?e.target.closest('a[download]'):null;" +
            "if(a&&/^blob:/.test(a.href)&&window.AndroidBridge){e.preventDefault();e.stopPropagation();" +
            "fetch(a.href).then(function(r){return r.blob()}).then(function(b){" +
            "var rd=new FileReader();rd.onload=function(){" +
            "var b64=(rd.result.split(',')[1])||'';" +
            "AndroidBridge.saveFile(a.download||'file.bin',b64,b.type||'application/octet-stream');" +
            "};rd.readAsDataURL(b);}).catch(function(){});}},true);})();", null);
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
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Сохранено: Загрузки/" + name, Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                    "Ошибка сохранения файла", Toast.LENGTH_LONG).show());
            }
        }
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
