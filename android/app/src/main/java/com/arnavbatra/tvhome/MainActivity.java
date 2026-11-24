package com.arnavbatra.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;

/**
 * The whole launcher is one WebView showing web/index.html (bundled in assets).
 * This class only does two jobs the web page cannot: open other apps and open system settings.
 */
public class MainActivity extends Activity {

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // lets the page cache the last weather reading

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "Android");   // the page calls Android.launch(...)

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /** Pressing Home while we are already open lands here (launchMode=singleTask). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        web.evaluateJavascript("window.onHome && window.onHome()", null);
    }

    /** A home screen should never "go back" out of itself. */
    @Override
    public void onBackPressed() { /* intentionally empty */ }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show(); }
        });
    }

    private class Bridge {
        /** json = ["pkg.one","pkg.two"]; opens the first package that is installed. */
        @JavascriptInterface
        public void launch(final String json) {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        JSONArray pkgs = new JSONArray(json);
                        PackageManager pm = getPackageManager();
                        for (int i = 0; i < pkgs.length(); i++) {
                            String pkg = pkgs.getString(i);
                            Intent in = pm.getLeanbackLaunchIntentForPackage(pkg);
                            if (in == null) in = pm.getLaunchIntentForPackage(pkg);
                            if (in != null) {
                                in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(in);
                                return;
                            }
                        }
                        Toast.makeText(MainActivity.this, "App not found on this TV", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "Could not open app", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        /** Not wired to any button yet; the safety hatch for the future "all apps / settings" screen. */
        @JavascriptInterface
        public void openSettings() {
            try {
                Intent in = new Intent(Settings.ACTION_SETTINGS);
                in.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(in);
            } catch (Exception e) {
                toast("Settings not available");
            }
        }
    }
}
