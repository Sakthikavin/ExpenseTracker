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

Output lands at `app/build/outputs/apk/debug/expense-tracker-debug.apk`. A `BUILD SUCCESSFUL` message
means it compiled; any errors will print above that line.

## 3. Install it

```bash
adb install -r app/build/outputs/apk/debug/expense-tracker-debug.apk
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

> **The emulator console cannot send multi-line messages.** `adb emu sms send` truncates the body at
> the first newline, so an Axis or HDFC UPI block alert arrives as just its first line and lands in
> the review queue. That is a limitation of the console, not of the parser. Multi-line formats are
> covered on-device by `SmsRepositoryTest.ingestsMultiLineBlockFormatAlerts` (see §8), which feeds
> the real text through the real ingest path.

An NPS-style pair — one debit alert and its NEFT confirmation, sharing a reference — now produces
**two** transactions, a ₹5,000 debit and a ₹5,000 credit. That's the documented behaviour since
parsing moved to the published rule list; collapsing it is a console action (a discarded sender or an
ignore rule). Sending the *same* message twice should still produce one:

```bash
adb emu sms send HDFCBK "UPDATE: INR 5,000.00 debited from HDFC Bank XX3941 on 05-MAR-26. Info: NEFT Dr-UTIB0CCH274-A B C-HDFCH00842011992-NET BANKING SI -NPS Contribution M. Avl bal:INR 84,966.79"
adb emu sms send HDFCBK "HDFC Bank : NEFT money transfer Txn No HDFCH00842011992 for Rs INR 5,000.00 has been credited to A B C on 05-03-2026 at 04:01:54"
```

## 7. What to check

**Categories tab**
- Default categories (Food & Dining, Groceries, Transport, Shopping, Bills & Utilities,
  Entertainment, Health, Rent & Housing, Investments, Transfers, Salary) show up
  automatically with distinct colored icons — no need to create them yourself.
- These are seeded once, when the database is first created. Adding to the list only affects
  a **fresh install** (or after `pm clear`); an existing database keeps the categories it has.
- Tap **+** to add a new category: you can pick both an icon and a color before saving.

**Transactions tab**
- Each row now shows the transaction date above the merchant name.
- Tap the category badge/text under a merchant name (e.g. "Unassigned") to open a dropdown
  and (re)categorize it — the change saves immediately. The category's icon shows both on
  the row and in the dropdown list.

**Transfers — gone on purpose**
- There is no "My accounts" screen and no transfer pairing any more. Money moved between your own
  accounts is an ordinary debit and an ordinary credit, and both count. Check the upgrade too:
  anything paired before this release is now two separate rows, so those months report more
  spending and more income than they used to.

**Messages I skipped** (Settings)
- Lists what the parser turned away while still mentioning money — a promotion with a price in it, a
  balance enquiry. Each row has **Move to review** for when the heuristic was wrong.

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

## 8. Run the automated tests

The SMS pipeline is covered by two suites. Run both before shipping a parser change.

**Unit tests** — pure Kotlin, no emulator needed, a few seconds:

```bash
./gradlew :app:testDebugUnitTest
```

Covers parsing per message format against the **real published rules** (copied into
`src/test/resources`), date extraction, amount conversion, redaction faithfulness and pattern
generalisation. When a new bank format shows up, add the message to `RealMessages` *first* and watch
`RealMessageTest` fail — then write the rule on the console and refresh the copy, rather than
changing a regex here.

**Instrumentation tests** — need the emulator from step 1 running:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Covers every database migration against a real old database, and the ingest path end to end
(deduplication, raw↔transaction linking, dates, the skipped-message bucket, and the
confirm-then-auto-parse learning loop).

To run a single class:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.expensetracker.data.local.MigrationTest
```

> `--tests` does **not** work on `connectedDebugAndroidTest`; use the runner argument above.

**Migration check before installing over real data.** `MigrationTest` proves the upgrade in
isolation, but if you have a build with real transactions on your phone, install the new APK over it
(not after `pm clear`) and confirm the app opens with the transactions intact.

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
