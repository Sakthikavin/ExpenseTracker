# Installing the app on your phone via Google Drive

No USB cable needed — build the APK on this Mac, upload it to Drive, then download and
install it on your phone.

## 1. Build the APK on the Mac

From the project root:

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
```

The file lands at:

```
app/build/outputs/apk/debug/app-debug.apk
```

A debug build is perfectly fine to sideload and use day-to-day — no need to fuss with
release signing for personal use.

## 2. Upload it to Google Drive

Pick whichever is easiest:

- **Browser**: open [drive.google.com](https://drive.google.com), drag `app-debug.apk`
  into a folder (e.g. a folder named "APKs").
- **Drive desktop app**: if you have Google Drive syncing a local folder on this Mac, just
  copy `app-debug.apk` into that synced folder — it uploads automatically:
  ```bash
  cp app/build/outputs/apk/debug/app-debug.apk ~/Google\ Drive/My\ Drive/APKs/
  ```
  (adjust the path to wherever your Drive folder actually syncs to).

## 3. Download it on your phone

1. Open the **Google Drive** app on your phone.
2. Find `app-debug.apk` and tap it.
3. Tap the **download** icon (⬇) in the top-right of the preview screen.

## 4. Allow installing from Drive (first time only)

Android blocks installs from apps other than the Play Store by default. The first time you
try to install:

1. Tap the downloaded APK notification (or find it in your **Files**/**Downloads** app).
2. Android will show "For your security, your phone is not allowed to install unknown apps
   from this source" — tap **Settings** on that prompt.
3. Toggle **Allow from this source** on for the Google Drive app (or Files app, whichever
   you used to open it).
4. Go back and tap the APK again — the normal install screen now appears.

## 5. Install

Tap **Install**. Once done, tap **Open** to launch it, or find "Expense Tracker" in your
app drawer.

## Updating later

Repeat steps 1–5 with a freshly built APK. Android will offer to **update** the existing
app rather than install a duplicate — your data (transactions, categories, budgets) is
preserved across updates as long as you don't uninstall first.

## Granting SMS permissions on your real phone

Unlike the emulator, there's no `adb emu sms send` shortcut here — real bank/UPI SMS will
trigger the app's parser automatically once you grant the permission. The app asks for
SMS access via a system dialog the first time you open it after install; tap **Allow**
(or "While using the app"). If you accidentally deny it, you can re-grant manually via:

**Settings → Apps → Expense Tracker → Permissions → SMS → Allow**
