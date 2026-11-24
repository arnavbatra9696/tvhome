package com.arnavbatra.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The whole launcher is one WebView showing web/index.html (bundled in assets).
 * This class only does two jobs the web page cannot: open other apps and open system settings.
 */
public class MainActivity extends Activity {

    private WebView web;
    private NowPlaying nowPlaying;
    private boolean pageReady = false;
    private String pendingNowPlaying = null;

    @Override
    protected void onResume() {
        super.onResume();
        nowPlaying.start();              // only while the home screen is in front
        // back on the home screen with music still playing: the page goes to the vinyl screen
        if (pageReady) web.evaluateJavascript("window.onResumeHome && window.onResumeHome()", null);
    }

    @Override
    protected void onPause() {
        nowPlaying.stop();               // nothing runs while you're watching something else
        if (pageReady) web.evaluateJavascript("window.onLeaveHome && window.onLeaveHome()", null);
        super.onPause();
    }

    /** Hands now-playing JSON to the page (queued until the page has loaded). */
    private void pushNowPlaying(final String json) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (!pageReady) { pendingNowPlaying = json; return; }
                web.evaluateJavascript("window.onNowPlaying && window.onNowPlaying(" + json + ")", null);
            }
        });
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // lets the page cache the last weather reading
        s.setMediaPlaybackRequiresUserGesture(false);   // the interface sounds (a remote press isn't a "tap")

        nowPlaying = new NowPlaying(this, new NowPlaying.Sink() {
            @Override public void push(String json) { pushNowPlaying(json); }
        });

        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingNowPlaying != null) { String j = pendingNowPlaying; pendingNowPlaying = null; pushNowPlaying(j); }
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");   // the page calls Android.launch(...)

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
        handleHomeAliasSwitch(getIntent());
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

    /**
     * Lets adb switch this app's Home entry (.HomeAlias) on or off. Android 14 refuses `pm enable` on a
     * single component from the shell, but an app may change its own components:
     *   adb shell am start -n com.arnavbatra.tvhome/.MainActivity --es tvhome.homeAlias on    (or off)
     */
    private void handleHomeAliasSwitch(Intent intent) {
        String v = intent == null ? null : intent.getStringExtra("tvhome.homeAlias");
        if (v == null) return;
        boolean on = "on".equals(v);
        getPackageManager().setComponentEnabledSetting(
                new android.content.ComponentName(this, "com.arnavbatra.tvhome.HomeAlias"),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        Toast.makeText(this, on ? "Home: this app can now be the Home screen" : "Home: back to a normal app", Toast.LENGTH_LONG).show();
    }

    /** Pressing Home while we are already open lands here (launchMode=singleTask). */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleHomeAliasSwitch(intent);
        web.evaluateJavascript("window.onHome && window.onHome()", null);
    }

    /** A home screen should never "go back" out of itself; the page uses Back to close the app drawer. */
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack && window.onBack()", null);
    }

    /** Draws a launcher banner/icon into a PNG data: URL the page can show in an <img>. */
    private static String toDataUrl(Drawable d, int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, bos);
        b.recycle();
        return "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

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

        /**
         * Every app with a TV launch entry (what the stock "Your apps" grid shows), sorted by name:
         * [{"pkg","label","banner":true|false,"img":"data:image/png;base64,..."}]. Our own app is left out.
         */
        @JavascriptInterface
        public String apps() {
            JSONArray out = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
                List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
                Collections.sort(list, new ResolveInfo.DisplayNameComparator(pm));
                Set<String> seen = new HashSet<>();
                for (ResolveInfo ri : list) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                    Drawable d = ri.activityInfo.loadBanner(pm);
                    if (d == null) d = ri.activityInfo.applicationInfo.loadBanner(pm);
                    boolean banner = d != null;
                    if (d == null) d = ri.loadIcon(pm);
                    JSONObject o = new JSONObject();
                    o.put("pkg", pkg);
                    o.put("label", ri.loadLabel(pm).toString());
                    o.put("banner", banner);
                    o.put("img", toDataUrl(d, banner ? 320 : 128, banner ? 180 : 128));
                    out.put(o);
                }
            } catch (Exception e) {
                // an empty list just shows "No apps found"; never crash the home screen
            }
            return out.toString();
        }

        /** Bass loudness of what the TV is playing, 0..1 (0 when the visualizer isn't available). Polled per frame. */
        @JavascriptInterface
        public float level() { return nowPlaying.level(); }

        /** "toggle" | "next" | "prev" on the current media session. */
        @JavascriptInterface
        public void media(final String cmd) {
            runOnUiThread(new Runnable() { @Override public void run() { nowPlaying.command(cmd); } });
        }

        /** Brings the app that is playing to the front. */
        @JavascriptInterface
        public void openPlaying() {
            String pkg = nowPlaying.packageName();
            if (pkg != null) launch(new JSONArray().put(pkg).toString());
        }

        /**
         * The "All apps" tile: opens the stock Google TV home screen (where every app is), even after this
         * launcher becomes the default Home. Returns false if it isn't there (e.g. disabled), so the page
         * can show its own drawer instead.
         */
        @JavascriptInterface
        public boolean openStockHome() {
            try {
                Intent in = new Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .setPackage("com.google.android.apps.tv.launcherx")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (getPackageManager().resolveActivity(in, 0) == null) return false;
                startActivity(in);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Opens the TV's Settings app (the "Settings" pill on the home screen). */
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
