package com.tungsten.fcl.lumen;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 界面外壳：承载 Lumen 的 HTML UI。
 *
 * 刻意用 android.app.Activity + 系统主题，不继承 FCL 的 AppCompat 基类，
 * 这样这个类对 FCL 内部实现零依赖，第一次构建就能过。
 * 等功能绑定稳定后，再换成 FCL 的主题体系。
 */
public class WebShellActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(Color.TRANSPARENT);
        w.setNavigationBarColor(Color.TRANSPARENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            w.setNavigationBarContrastEnforced(false);
        } else {
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#FF07070D"));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.requestApplyInsets();
            }
        });
        web.setWebChromeClient(new WebChromeClient());

        setContentView(web);

        web.addJavascriptInterface(new UiBridge(this, web), "LumenBridge");

        web.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int top = 0, bottom = 0;
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets i = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    top = i.top;
                    bottom = i.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                final String js = "document.documentElement.style.setProperty('--sat','" + top + "px');"
                        + "document.documentElement.style.setProperty('--sab','" + bottom + "px');";
                web.evaluateJavascript(js, null);
                return insets;
            }
        });

        web.loadUrl("file:///android_asset/lumen/index.html");

        web.post(new Runnable() {
            @Override
            public void run() {
                web.requestApplyInsets();
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
