# Installing the app on your phone

Two ways to get the APK onto your phone: download a prebuilt one from GitHub Releases
(no Mac needed), or build it yourself and transfer it via Google Drive.

## Option A: Download from GitHub Releases

[.github/workflows/release.yml](.github/workflows/release.yml) builds a signed release APK
and attaches it to a GitHub Release whenever a tag like `v1.0` is pushed:

```bash
git tag v1.0
git push origin v1.0
```

Once the workflow finishes (check the **Actions** tab), the APK is attached to the release
at `https://github.com/Sakthikavin/ExpenseTracker/releases` — open that page in your phone's
browser, tap the `.apk` asset to download it, then jump to
[Allow installing from an unknown source](#4-allow-installing-first-time-only) below (the
same permission prompt appears regardless of which app downloaded the file).

## Option B: Build it yourself and transfer via Google Drive

No USB cable needed — build the APK on this Mac, upload it to Drive, then download and
install it on your phone.

### 1. Build the APK on the Mac

From the project root:
    
```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew :app:assembleDebug
```

The file lands at:

```
app/build/outputs/apk/debug/expense-tracker-debug.apk
```

A debug build is perfectly fine to sideload and use day-to-day — no need to fuss with
release signing for personal use.

### 2. Upload it to Google Drive

Pick whichever is easiest:

- **Browser**: open [drive.google.com](https://drive.google.com), drag `expense-tracker-debug.apk`
  into a folder (e.g. a folder named "APKs").
- **Drive desktop app**: if you have Google Drive syncing a local folder on this Mac, just
  copy `expense-tracker-debug.apk` into that synced folder — it uploads automatically:
  ```bash
  cp app/build/outputs/apk/debug/expense-tracker-debug.apk ~/Google\ Drive/My\ Drive/APKs/
  ```
  (adjust the path to wherever your Drive folder actually syncs to).

### 3. Download it on your phone

1. Open the **Google Drive** app on your phone.
2. Find `expense-tracker-debug.apk` and tap it.
3. Tap the **download** icon (⬇) in the top-right of the preview screen.

### 4. Allow installing (first time only)

Android blocks installs from apps other than the Play Store by default. The first time you
try to install:

1. Tap the downloaded APK notification (or find it in your **Files**/**Downloads** app).
2. Android will show "For your security, your phone is not allowed to install unknown apps
   from this source" — tap **Settings** on that prompt.
3. Toggle **Allow from this source** on for whichever app you downloaded it with (Google
   Drive, Chrome, Files — whichever opened the file).
4. Go back and tap the APK again — the normal install screen now appears.

### 5. Install

Tap **Install**. Once done, tap **Open** to launch it, or find "Expense Tracker" in your
app drawer.

## Updating later

**From a release:** push a new tag (`git tag v1.1 && git push origin v1.1`) and repeat
Option A. Android offers to **update** the existing app rather than install a duplicate —
your data (transactions, categories, budgets) survives, as long as you don't uninstall
first. Every release is signed with the same key, which is what makes that possible.

**From a local build:** repeat Option B's steps with a freshly built APK. A local debug
build is signed with this Mac's debug key, not the release key, so it **cannot** update an
install that came from a GitHub Release — Android refuses with "package conflicts with an
existing package". Switching between the two means uninstalling first, and losing the data
with it. Pick one source and stay on it.

## Granting SMS permissions on your real phone

Unlike the emulator, there's no `adb emu sms send` shortcut here — real bank/UPI SMS will
trigger the app's parser automatically once you grant the permission. The app asks for
SMS access via a system dialog the first time you open it after install; tap **Allow**
(or "While using the app"). If you accidentally deny it, you can re-grant manually via:

**Settings → Apps → Expense Tracker → Permissions → SMS → Allow**
