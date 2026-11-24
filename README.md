# TV Home

A custom Google TV home screen: a greeting, clock, weather and six app tiles.
The UI is plain HTML/CSS/JS in `web/`. A tiny Android app in `android/` wraps it in a WebView and opens apps for it.

```
web/index.html      the whole UI (edit the CONFIG block at the top of the <script>)
web/assets/         backgrounds, fonts, tile shadow, Hotstar logos
android/            the WebView shell (one Java file, one manifest)
```

## 1. Try the UI in a browser first (2 minutes)

Open `web/index.html` in Chrome. Arrow keys move focus, Enter "opens" an app (a toast, since there is no TV).
Test other situations with URL parameters:

- `index.html?t=08:15&wx=45` fakes the time and weather (45 = haze, 61 = rain, 0 = clear)
- `index.html?idle=5` starts the screensaver after 5 seconds

## 2. Get the APK

Option A, no installs: push this folder to a GitHub repo, open the Actions tab, run "Build APK", download `app-debug.apk` from the run.

Option B: open the `android/` folder in Android Studio, then Build > Build APK(s).

## 3. Install it as a normal app (safe)

On the TV: Developer options > USB debugging on (and network debugging if offered). Same Wi-Fi as your computer.

```
adb connect <TV_IP>:5555
adb install -r app-debug.apk
```

"Home" now appears in the TV's app row. Open it from there. Your real home screen is untouched.

## 4. Fix the app package names

The tiles open apps by package name, and three of them are guesses. On the TV:

```
adb shell pm list packages | grep -iE "youtube|netflix|hotstar|amazon|zee|sonyliv"
```

Put what you find into the `APPS` list in `web/index.html`, rebuild, reinstall.

## 5. Making it the real home screen (do this LAST)

Do not do this until the launcher has a way to reach Settings and all apps. Right now it has six tiles and nothing else.

```
# find the current home screen package first and write it down
adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME

# allow this app to be a home screen
adb shell pm enable com.arnavbatra.tvhome/.HomeAlias
```

Pressing Home may now show a chooser. To make it permanent you disable the old launcher (use the package name from the first command):

```
adb shell pm disable-user --user 0 <old.launcher.package>
```

### Undo everything

```
adb shell pm enable <old.launcher.package>
adb shell pm disable com.arnavbatra.tvhome/.HomeAlias
adb uninstall com.arnavbatra.tvhome
```

Keep debugging switched on until you are sure it all works. Turn it off when you are done.
