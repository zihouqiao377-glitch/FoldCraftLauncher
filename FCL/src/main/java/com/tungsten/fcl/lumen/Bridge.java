package com.tungsten.fcl.lumen;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;

/**
 * JS ↔ 原生 的桥。
 *
 * 为什么必须走原生而不是页面里直接 fetch：
 * 页面是从 file:///android_asset 加载的，origin 是 null，
 * 任何跨域请求都会被 CORS 拦掉。原生这层没有这个限制。
 *
 * 协议（单向调用 + 回调）：
 *   JS:  LumenBridge.call(reqId, op, a, b, c)
 *   原生: window.__native.resolve(reqId, ok, payload)
 *         window.__native.progress(reqId, percent, bytes)
 */
public class Bridge {

    private final Context ctx;
    private final WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public Bridge(Context ctx, WebView web) {
        this.ctx = ctx;
        this.web = web;
    }

    @JavascriptInterface
    public void call(final String id, final String op, final String a, final String b, String c) {
        pool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    handle(id, op, a, b);
                } catch (Exception e) {
                    fail(id, e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        });
    }

    private void handle(String id, String op, String a, String b) throws Exception {
        switch (op) {
            case "get":
                ok(id, http(a));
                break;

            case "download":
                download(id, a, b);
                break;

            case "prefs.get":
                ok(id, prefs().getString(a, ""));
                break;

            case "prefs.set":
                prefs().edit().putString(a, b).apply();
                ok(id, "1");
                break;

            case "dir":
                ok(id, dataDir().getAbsolutePath());
                break;

            case "exists":
                ok(id, new File(a).exists() ? "1" : "0");
                break;

            case "size":
                ok(id, String.valueOf(new File(a).length()));
                break;

            case "mkdirs":
                ok(id, new File(a).mkdirs() ? "1" : "0");
                break;

            case "delete":
                ok(id, deleteTree(new File(a)) ? "1" : "0");
                break;

            case "list": {
                // 返回 [{name, size, dir}, ...]
                JSONArray arr = new JSONArray();
                File[] files = new File(a).listFiles();
                if (files != null) {
                    for (File f : files) {
                        JSONObject o = new JSONObject();
                        o.put("name", f.getName());
                        o.put("size", f.isDirectory() ? 0 : f.length());
                        o.put("dir", f.isDirectory());
                        arr.put(o);
                    }
                }
                ok(id, arr.toString());
                break;
            }

            case "open": {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(a));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
                ok(id, "1");
                break;
            }

            case "launchPkg": {
                Intent li = ctx.getPackageManager().getLaunchIntentForPackage(a);
                if (li == null) {
                    ok(id, "0");
                } else {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(li);
                    ok(id, "1");
                }
                break;
            }

            case "openClassic": {
                Intent ci = new Intent();
                ci.setClassName(ctx, "com.tungsten.fcl.activity.SplashActivity");
                ci.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(ci);
                ok(id, "1");
                break;
            }

            case "toast":
                toast(a);
                ok(id, "1");
                break;

            default:
                fail(id, "unknown op: " + op);
        }
    }

    // ---------------- 网络 ----------------

    private String http(String url) throws Exception {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                throw new IllegalStateException("HTTP " + code + " " + url);
            }
            InputStream in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            return readAll(in);
        } finally {
            c.disconnect();
        }
    }

    private void download(String id, String url, String path) throws Exception {
        File out = new File(path);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建目录: " + parent);
        }
        // 已存在则跳过（下载器幂等）
        File part = new File(path + ".part");

        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) {
                throw new IllegalStateException("HTTP " + code + " " + url);
            }
            long total = c.getContentLengthLong();
            InputStream in = new BufferedInputStream(c.getInputStream(), 65536);
            FileOutputStream fos = new FileOutputStream(part);
            byte[] buf = new byte[65536];
            long done = 0;
            long lastEmit = 0;
            int n;
            try {
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastEmit > 120) {
                        lastEmit = now;
                        int pct = total > 0 ? (int) (done * 100 / total) : -1;
                        progress(id, pct, done);
                    }
                }
                fos.flush();
                fos.getFD().sync();
            } finally {
                fos.close();
                in.close();
            }
            if (out.exists() && !out.delete()) {
                throw new IllegalStateException("无法覆盖旧文件: " + out);
            }
            if (!part.renameTo(out)) {
                throw new IllegalStateException("重命名失败: " + part);
            }
            progress(id, 100, total > 0 ? total : done);
            ok(id, String.valueOf(done));
        } finally {
            c.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "LumenLauncher/1.1 (Android)");
        c.setRequestProperty("Accept-Encoding", "gzip");
        return c;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return new String(bos.toByteArray(), "UTF-8");
    }

    // ---------------- 存储 ----------------

    private File dataDir() {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) {
            base = ctx.getFilesDir();
        }
        File dir = new File(base, "lumen");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences("lumen", Context.MODE_PRIVATE);
    }

    private static boolean deleteTree(File f) {
        if (!f.exists()) {
            return true;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteTree(k);
                }
            }
        }
        return f.delete();
    }

    // ---------------- 回调 ----------------

    private void ok(String id, String payload) {
        emit("window.__native&&window.__native.resolve("
                + JSONObject.quote(id) + ",true," + JSONObject.quote(payload == null ? "" : payload) + ")");
    }

    private void fail(String id, String message) {
        emit("window.__native&&window.__native.resolve("
                + JSONObject.quote(id) + ",false," + JSONObject.quote(message == null ? "未知错误" : message) + ")");
    }

    private void progress(String id, int percent, long bytes) {
        emit("window.__native&&window.__native.progress("
                + JSONObject.quote(id) + "," + percent + "," + bytes + ")");
    }

    private void emit(final String js) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (web != null) {
                    web.evaluateJavascript(js, null);
                }
            }
        });
    }

    private void toast(final String msg) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
