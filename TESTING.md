# Manually building and testing on the Pixel 9a emulator

This walks through building the debug APK from the command line, installing it on your
`Pixel_9a` emulator, and exercising the features we've added. Run everything from the
project root (`/Users/sakthikavinss/AndroidStudioProjects/ExpenseTracker`) in a terminal.

## 0. One-time setup

Point `adb`/`emulator` at your SDK for this terminal session (or add it to your shell
profile so you don't repeat it):

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

## 1. Start the emulator

If it's not already running:

```bash
emulator -list-avds        # confirms the AVD is named Pixel_9a
emulator -avd Pixel_9a &
```

Wait for it to fully boot, then confirm it's visible to adb:

```bash
adb devices                # should list "emulator-5554  device"
```

## 2. Build the debug APK

```bash
./gradlew :app:assembleDebug
```

Output lands at `app/build/outputs/apk/debug/app-debug.apk`. A `BUILD SUCCESSFUL` message
means it compiled; any errors will print above that line.

## 3. Install it

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`-r` reinstalls over an existing copy and **keeps existing app data** (your categories,
transactions, etc.). Skip to step 5 if you want to preserve data between builds.

### Optional: start from a clean slate

If you want fresh default categories reseeded (or are debugging seed logic), wipe app data
after installing:

```bash
adb shell pm clear com.example.expensetracker
```

This also resets the SMS runtime permissions (see step 5).

## 4. Launch it

```bash
adb shell am start -n com.example.expensetracker/.MainActivity
```

Or just tap the app icon in the emulator's app drawer.

## 5. Grant SMS permissions

The app asks for `RECEIVE_SMS` / `READ_SMS` (and `POST_NOTIFICATIONS` on Android 13+) via a
system dialog the moment `MainActivity` opens. **Tap "Allow" / "While using the app"** on
that dialog — without it, SMS auto-detection silently stays off (no crash, just no
transactions get created from incoming messages).

If you dismissed the dialog and want to grant it after the fact without reinstalling:

```bash
adb shell pm grant com.example.expensetracker android.permission.RECEIVE_SMS
adb shell pm grant com.example.expensetracker android.permission.READ_SMS
```

## 6. Simulate an incoming bank SMS (emulator only)

Real SMS can't reach an emulator, but the emulator console can fake one:

```bash
adb emu sms send HDFCBK "Rs.450.00 debited to AMAZON on 01-08-26. Avl Bal Rs 5000.00"
adb emu sms send SBIBNK "Rs.2500.00 credited from Jane Doe on 01-08-26. Avl Bal Rs 7500.00"
```

The sender ("HDFCBK", "SBIBNK") and body can be anything reasonably bank-alert-shaped —
the parser looks for `Rs <amount> debited/credited ... to/from/at <name>`. These should
appear in the Transactions tab within a second or two.

## 7. What to check

**Categories tab**
- Default categories (Food & Dining, Groceries, Transport, Shopping, Bills & Utilities,
  Entertainment, Health, Rent & Housing, Salary) show up automatically with distinct
  colored icons — no need to create them yourself.
- Tap **+** to add a new category: you can pick both an icon and a color before saving.

**Transactions tab**
- Each row now shows the transaction date above the merchant name.
- Tap the category badge/text under a merchant name (e.g. "Unassigned") to open a dropdown
  and (re)categorize it — the change saves immediately. The category's icon shows both on
  the row and in the dropdown list.

**Budgets tab and Review tab**
- Category icons now show next to category names here too (budget rows, and the category
  picker when confirming a review item).

**Dashboard tab**
- Defaults to "This month", shown as a date range under the filter chips.
- Tap "Custom range" to open a date-range picker and pick any start/end date.
- "Spend by category" has a **Chart / Table** toggle:
  - **Chart**: a donut chart colored per category, with the total in the center, and a
    legend below showing each category's icon, name, percentage of total spend, and amount.
  - **Table**: the same data as icon + name + percentage + amount rows, each with a colored
    progress bar sized to its share of the total.

## Troubleshooting

**Watch logs for crashes while testing:**
```bash
adb logcat -s AndroidRuntime:E ActivityManager:I
```

**Confirm the app process is alive:**
```bash
adb shell pidof com.example.expensetracker
```

**Inspect the on-device database directly** (useful if the UI shows something unexpected):
```bash
adb shell "run-as com.example.expensetracker cat databases/expense_tracker.db" > /tmp/expense_tracker.db
adb shell "run-as com.example.expensetracker cat databases/expense_tracker.db-wal" > /tmp/expense_tracker.db-wal
sqlite3 /tmp/expense_tracker.db "SELECT * FROM transactions;"
```

**Take a screenshot of whatever's on screen:**
```bash
adb shell screencap -p /sdcard/screen.png
adb pull /sdcard/screen.png .
```
