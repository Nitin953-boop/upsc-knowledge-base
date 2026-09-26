package com.upsc.notemaker74;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.webkit.JavascriptInterface;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    WebView w;
    ValueCallback<Uri[]> fileCallback;
    static final int PICK_FILE = 2001;
    static final long MAX_BYTES = 100L * 1024L * 1024L;
    File attachmentDir;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        attachmentDir = new File(getFilesDir(), "attachments");
        if (!attachmentDir.exists()) attachmentDir.mkdirs();
        w = new WebView(this);
        w.setBackgroundColor(Color.WHITE);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        w.addJavascriptInterface(new NativeBridge(), "NativeBridge");
        w.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                final String prefix = "https://notemaker.local/attachments/";
                if (url != null && url.startsWith(prefix)) {
                    String id = url.substring(prefix.length()).split("[?#]",2)[0];
                    File f = new File(attachmentDir, safe(id));
                    try {
                        if (f.isFile()) {
                            String mime = guessMime(f.getName());
                            return new WebResourceResponse(mime, "UTF-8", new FileInputStream(f));
                        }
                    } catch (Exception ignored) {}
                }
                return super.shouldInterceptRequest(view, url);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                String u = req.getUrl().toString();
                if (u.startsWith("https://notemaker.local/")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, req.getUrl())); } catch(Exception ignored) {}
                return true;
            }
        });
        w.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    Intent i = params.createIntent();
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(i, PICK_FILE);
                    return true;
                } catch(Exception e) { fileCallback = null; return false; }
            }
        });
        setContentView(w);
        w.loadUrl("file:///android_asset/editor.html");
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PICK_FILE) {
            Uri u = null;
            if (resultCode == RESULT_OK && data != null) u = data.getData();
            if (u != null) importAttachment(u);
            if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    void importAttachment(Uri uri) {
        new Thread(() -> {
            String name = queryName(uri);
            String mime = getContentResolver().getType(uri);
            if (mime == null) mime = "application/octet-stream";
            long size = querySize(uri);
            if (size > MAX_BYTES) { runJs("window.onNativeAttachmentError('File is larger than 100 MB.');"); return; }
            String id = UUID.randomUUID().toString();
            File out = new File(attachmentDir, id);
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                if (in == null) throw new IOException("Unable to open file");
                byte[] buf = new byte[64 * 1024]; int n; long total = 0;
                while ((n = in.read(buf)) != -1) { total += n; if (total > MAX_BYTES) throw new IOException("File is larger than 100 MB."); os.write(buf,0,n); }
                String finalName = name.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
                String finalMime = mime.replace("\\", "\\\\").replace("'", "\\'");
                runJs("window.onNativeAttachment('"+id+"','"+finalName+"','"+finalMime+"',"+total+")");
            } catch(Exception e) { out.delete(); runJs("window.onNativeAttachmentError('Could not import that file.');"); }
        }).start();
    }

    void runJs(String js) { runOnUiThread(() -> w.evaluateJavascript("javascript:" + js, null)); }

    String queryName(Uri u) { String n = null; try { android.database.Cursor c=getContentResolver().query(u,null,null,null,null); if(c!=null){int i=c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if(c.moveToFirst()&&i>=0)n=c.getString(i); c.close();}}catch(Exception ignored){} return n==null?"attachment":n; }
    long querySize(Uri u) { try { android.database.Cursor c=getContentResolver().query(u,null,null,null,null); if(c!=null){int i=c.getColumnIndex(android.provider.OpenableColumns.SIZE); if(c.moveToFirst()&&i>=0&&!c.isNull(i)){long x=c.getLong(i);c.close();return x;} c.close();}}catch(Exception ignored){} return 0; }
    static String safe(String s) { return s.replace("/","").replace("\\","").replace("..",""); }
    static String guessMime(String n) { String x=n.toLowerCase(); if(x.endsWith(".jpg")||x.endsWith(".jpeg"))return"image/jpeg";if(x.endsWith(".png"))return"image/png";if(x.endsWith(".gif"))return"image/gif";if(x.endsWith(".webp"))return"image/webp";if(x.endsWith(".mp4"))return"video/mp4";if(x.endsWith(".webm"))return"video/webm";if(x.endsWith(".mp3"))return"audio/mpeg";if(x.endsWith(".pdf"))return"application/pdf";return"application/octet-stream"; }

    public class NativeBridge {
        @JavascriptInterface public String attachmentUrl(String id) { return "https://notemaker.local/attachments/" + safe(id); }
        @JavascriptInterface public void printNote() {
            runOnUiThread(() -> {
                try {
                    PrintManager pm = (PrintManager)getSystemService(PRINT_SERVICE);
                    PrintDocumentAdapter adapter = w.createPrintDocumentAdapter("UPSC NoteMaker");
                    pm.print("UPSC NoteMaker", adapter, new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS).build());
                } catch(Exception ignored) {}
            });
        }
    }

    @Override public void onBackPressed() { if (w.canGoBack()) w.goBack(); else super.onBackPressed(); }
}
