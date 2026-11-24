package com.arnavbatra.tvhome;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so Android lets the launcher read other apps' media sessions (what's playing, cover art).
 * MediaSessionManager.getActiveSessions() requires an enabled notification listener from the same app.
 * It ignores notifications themselves.
 *
 * Grant:  adb shell cmd notification allow_listener com.arnavbatra.tvhome/.MediaListener
 * Revoke: adb shell cmd notification disallow_listener com.arnavbatra.tvhome/.MediaListener
 */
public class MediaListener extends NotificationListenerService {
}
