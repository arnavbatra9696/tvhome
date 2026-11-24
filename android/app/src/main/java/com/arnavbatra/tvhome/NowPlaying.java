package com.arnavbatra.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.audiofx.Visualizer;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.List;

/**
 * Watches other apps' media sessions (Spotify, YouTube Music, ...) and the TV's audio output.
 *
 * - State (title, artist, cover art, playing/paused, position) is pushed to the page as JSON via {@link Sink}.
 * - Loudness of the bass is exposed as {@link #level()} (0..1) for the page to poll each animation frame.
 *
 * Both need permissions granted over adb (see README): notification access for the sessions, and
 * RECORD_AUDIO for the Visualizer. The Visualizer reads the output mix (session 0), never the microphone.
 * Without them everything here quietly does nothing and the page shows no Now Playing panel / a soft pulse.
 */
final class NowPlaying {

    interface Sink { void push(String json); }

    private final Context ctx;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ComponentName listener;

    private MediaSessionManager msm;
    private MediaController ctl;
    private Visualizer viz;
    private volatile float level = 0f;
    private volatile boolean reactive = false;
    private float peak = 1f;

    private String artKey = "";        // title|artist the cached art belongs to
    private String artData = "";       // data: URL of the current cover

    NowPlaying(Context ctx, Sink sink) {
        this.ctx = ctx;
        this.sink = sink;
        this.listener = new ComponentName(ctx, MediaListener.class);
    }

    // ---------------------------------------------------------------- lifecycle

    void start() {
        try {
            msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            msm.addOnActiveSessionsChangedListener(sessions, listener, main);
            onSessions(msm.getActiveSessions(listener));
        } catch (SecurityException e) {
            // notification access not granted yet: no Now Playing, nothing else breaks
            msm = null;
            pushJson("{\"active\":false,\"access\":false}");
        }
        startVisualizer();
    }

    void stop() {
        if (msm != null) {
            try { msm.removeOnActiveSessionsChangedListener(sessions); } catch (Exception ignored) { }
        }
        setController(null);
        stopVisualizer();
    }

    float level() { return level; }
    boolean reactive() { return reactive; }

    void command(String cmd) {
        MediaController c = ctl;
        if (c == null) return;
        MediaController.TransportControls t = c.getTransportControls();
        PlaybackState st = c.getPlaybackState();
        switch (cmd) {
            case "toggle":
                if (st != null && st.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
                break;
            case "next": t.skipToNext(); break;
            case "prev": t.skipToPrevious(); break;
            default: break;
        }
    }

    String packageName() { MediaController c = ctl; return c == null ? null : c.getPackageName(); }

    // ---------------------------------------------------------------- sessions

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::onSessions;

    private void onSessions(List<MediaController> list) {
        MediaController pick = null;
        if (list != null) {
            for (MediaController c : list) {                 // prefer one that is actually playing
                PlaybackState s = c.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { pick = c; break; }
            }
            if (pick == null && !list.isEmpty()) pick = list.get(0);
        }
        setController(pick);
        pushState();
    }

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState state) { pushState(); }
        @Override public void onMetadataChanged(MediaMetadata metadata) { pushState(); }
        @Override public void onSessionDestroyed() { setController(null); pushState(); }
    };

    private void setController(MediaController c) {
        if (ctl != null && (c == null || !c.getSessionToken().equals(ctl.getSessionToken()))) {
            ctl.unregisterCallback(cb);
        }
        if (c != null && (ctl == null || !c.getSessionToken().equals(ctl.getSessionToken()))) {
            c.registerCallback(cb, main);
        }
        ctl = c;
    }

    private void pushState() {
        MediaController c = ctl;
        MediaMetadata md = c == null ? null : c.getMetadata();
        if (c == null || md == null) { pushJson("{\"active\":false,\"access\":true}"); return; }
        try {
            PlaybackState st = c.getPlaybackState();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            long pos = 0;
            float speed = 0f;
            if (st != null) {
                pos = st.getPosition();
                speed = playing ? st.getPlaybackSpeed() : 0f;
                if (playing && st.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * speed);
                }
            }
            String title = str(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String artist = str(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);

            JSONObject o = new JSONObject();
            o.put("active", true);
            o.put("access", true);
            o.put("playing", playing);
            o.put("title", title);
            o.put("artist", artist);
            o.put("album", str(md, MediaMetadata.METADATA_KEY_ALBUM));
            o.put("pkg", c.getPackageName());
            o.put("app", appLabel(c.getPackageName()));
            o.put("duration", md.getLong(MediaMetadata.METADATA_KEY_DURATION));
            o.put("position", Math.max(0, pos));
            o.put("speed", speed);
            o.put("reactive", reactive);

            String key = title + "|" + artist;
            if (key.equals(artKey)) {
                o.put("art", artData);
                pushJson(o.toString());
            } else {
                artKey = key;
                artData = "";
                Bitmap bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (bmp == null) bmp = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (bmp != null) {
                    artData = toDataUrl(bmp);
                    o.put("art", artData);
                    pushJson(o.toString());
                } else {
                    o.put("art", "");
                    pushJson(o.toString());
                    String uri = str(md, MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI,
                            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
                    if (!uri.isEmpty()) loadArtAsync(uri, key);
                }
            }
        } catch (Exception e) {
            pushJson("{\"active\":false,\"access\":true}");
        }
    }

    /** Cover only available as a URI: fetch it off the main thread, then push state again. */
    private void loadArtAsync(final String uri, final String key) {
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                Uri u = Uri.parse(uri);
                String scheme = u.getScheme() == null ? "" : u.getScheme();
                InputStream in = scheme.startsWith("http") ? new URL(uri).openStream()
                        : ctx.getContentResolver().openInputStream(u);
                if (in != null) { bmp = BitmapFactory.decodeStream(in); in.close(); }
            } catch (Exception ignored) { }
            final Bitmap got = bmp;
            main.post(() -> {
                if (got != null && key.equals(artKey)) { artData = toDataUrl(got); pushState(); }
            });
        }, "cover-art").start();
    }

    // ---------------------------------------------------------------- visualizer

    private void startVisualizer() {
        stopVisualizer();
        try {
            viz = new Visualizer(0);                                   // 0 = the whole output mix
            viz.setEnabled(false);
            viz.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);   // largest = finest bins (~47 Hz at 1024)
            viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] w, int rate) { }
                @Override public void onFftDataCapture(Visualizer v, byte[] fft, int rate) { onFft(fft); }
            }, Math.min(Visualizer.getMaxCaptureRate(), 20000), false, true);   // ~20 per second
            viz.setEnabled(true);
            reactive = true;
        } catch (Throwable t) {                                        // no permission, or the board refuses
            viz = null;
            reactive = false;
            level = 0f;
        }
    }

    private void stopVisualizer() {
        if (viz != null) {
            try { viz.setEnabled(false); viz.release(); } catch (Throwable ignored) { }
            viz = null;
        }
        level = 0f;
    }

    /** Bass energy (roughly 40-250 Hz) normalised against a slowly decaying peak, so quiet and loud songs both move. */
    private void onFft(byte[] fft) {
        if (fft == null || fft.length < 12) return;
        // bin k covers about k * sampleRate / captureSize; with 1024 samples at 48 kHz that's ~47 Hz per bin
        int bins = Math.max(2, Math.min(8, Math.round(250f * fft.length / 48000f)));
        float sum = 0f;
        for (int i = 2; i < 2 + bins * 2 && i + 1 < fft.length; i += 2) {   // (real, imaginary) pairs from bin 1
            float re = fft[i], im = fft[i + 1];
            sum += (float) Math.sqrt(re * re + im * im);
        }
        peak = Math.max(sum, peak * 0.992f);
        if (peak < 1f) peak = 1f;
        float l = sum / peak;
        level = l < 0f ? 0f : (l > 1f ? 1f : l);
    }

    // ---------------------------------------------------------------- helpers

    private void pushJson(String json) { sink.push(json); }

    private static String str(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return "";
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) { return ""; }
    }

    private static String toDataUrl(Bitmap src) {
        int max = 512;
        Bitmap b = src;
        if (src.getWidth() > max || src.getHeight() > max) {
            float s = Math.min((float) max / src.getWidth(), (float) max / src.getHeight());
            b = Bitmap.createScaledBitmap(src, Math.round(src.getWidth() * s), Math.round(src.getHeight() * s), true);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 88, bos);
        return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }
}
