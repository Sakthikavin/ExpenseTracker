# TODO

Agreed work not yet started. Design docs own the detail; this file owns the "not done yet".
Spec-level backlog lives with its spec — see `data/remoterules/FAITHFUL_REDACTION.md` §7–§8 and
[FUTURE_V2.md](FUTURE_V2.md) for v2 family sharing.

## Backup and restore

The database is the user's **only** copy of their financial history, and nothing in the app can
put it back. Today's whole answer is "Export all transactions to a local CSV" — transactions only,
and there is no import, so it's a readable record rather than a restore path. Everything that
isn't SMS-derived is unrecoverable: manually added transactions, hand-assigned categories, merchant
rules, budgets, bills, the own-accounts setup behind self-transfer detection, learned patterns.
(SMS-derived transactions can be rebuilt with Settings → Import SMS history, as long as the
messages are still in the phone's inbox.)

Wanted: export the database file to a location the user picks, and import one back. Points to
settle when it's built:

- Copy the file with SQLite's own backup/`VACUUM INTO`, or close the database first — a naive file
  copy of a live database with a WAL can restore as a corrupt or half-written file.
- Restoring has to replace the live database and then reopen it, which means either restarting the
  app or rebuilding the Room instance behind `AppDatabase.getInstance`'s singleton.
- An import must refuse a file from a **newer** schema version than the running app (Room can
  migrate forward, never back), and state plainly that it replaces everything rather than merging.
- The export is unencrypted financial history leaving the app's sandbox: say so at the point of
  export, and consider a passphrase.
- Related, from the upgrade discussion: `android:allowBackup="true"` with the generated sample rule
  files means Android's auto-backup already ships the database to Google's cloud by default. Decide
  deliberately whether that stays on, and exclude the database in `backup_rules.xml` /
  `data_extraction_rules.xml` if not.
- Also unrelated to backup but found alongside it: `versionCode` sat at `1` from the first release,
  so the platform could not recognise an older APK as a downgrade and would install it over a newer
  database — which throws rather than wiping, but leaves the app unusable until the right APK is
  back. Fixed by bumping it (now `2`, with `versionName` in step); the protection only applies from
  the next release onward, since the phone has to be running the higher code for the older one to
  be refused.

## Search by message text — Transactions tab, Review tab, Messages I skipped

Find a transaction or a queued message by typing words that appear in **the raw SMS**, not just
the merchant name. The message text is what the user actually remembers ("that Swiggy one with the
weird ref"), and it's the only searchable handle on a review-queue row, which has no merchant or
amount parsed out of it yet.

- **Review tab** — matches `raw_sms.body` (and probably `sender`) of the rows already on screen.
- **Messages I skipped** (`SkippedMessagesScreen`) — the same shape as the review tab, over
  `ParseStatus.DISCARDED` rows. Arguably needs it most: the list's whole job is answering "is the
  parser turning away anything from my bank?", and today that means reading up to 200 rows by eye.
  Searching by `sender` matters as much as by body here.
- **Transactions tab** — the body lives one hop away, on the `raw_sms` row a transaction points at
  via `rawSmsId`, and is loaded lazily per row today (`OriginalMessagePanel`). Searching it needs a
  query that reaches the message from the transaction, not the current per-row lazy load.
- Manually added transactions have no raw SMS, so decide whether they match on merchant/notes only
  or drop out of a text search entirely.
- Worth checking whether `LIKE '%term%'` over a few thousand rows is fast enough before reaching
  for an FTS table (an FTS index is a schema change, so it needs a Room migration).
