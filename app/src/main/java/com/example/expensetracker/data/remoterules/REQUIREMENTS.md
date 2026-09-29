# Remote Rule Sync & Submission — Requirements

Status: draft for review. This folder will hold the Kotlin implementation once the
design below is agreed; for now it holds only this spec.

## 1. Context

ExpenseTracker parses bank/UPI SMS in three tiers today (see `data/sms/SmsParser.kt`):

1. `BankTemplates` — built-in regex per bank, shipped in the APK.
2. `LearnedPatternDao` (via `PatternLearner`) — per-device patterns derived the first time
   *this phone's* user confirms a "needs review" message. Local only, never leaves the device.
3. Anything left over lands in the review queue (`ui/review/ReviewQueueScreen.kt`), status
   `ParseStatus.NEEDS_REVIEW` on `RawSmsEntity`.

This works, but two things don't scale once the app is shared with friends/family:

- A fix one person makes in tier 2 only helps *their* phone. Everyone else with the same
  bank hits the same review queue forever.
- Editing a message in the review queue today just fixes that one transaction — it doesn't
  turn into a reusable pattern unless PatternLearner's span-matching happens to succeed.

This spec adds a **fourth, shared tier**: rules curated by Sujith from real (redacted)
submissions, published as versioned JSON, pulled by every install. It sits between the
built-in templates and the per-device learned patterns.

## 2. Goals

- A message that fails to parse can be sent (redacted, with an optional note) to a small
  cloud store, without leaving the device un-actionable.
- Sujith reviews submissions in bulk (a separate web console — see the sibling repo) and
  publishes an updated rule set.
- Every install picks up new rules automatically (periodic background check) and on demand
  (a manual "Check for updates" action), and re-parses its own backlog immediately.
- No message content that could identify an amount's context beyond what's needed to write
  a regex — no balances, no full account numbers, no phone numbers — ever leaves the device.

## 3. Non-goals (for v1)

- No user-facing regex authoring inside the Android app itself (that stays your job, via
  the web console).
- No multi-tenant / multi-admin support — single reviewer (Sujith), single Firebase project.
- No automatic rule generation on-device; the LLM-assisted step happens in your review
  workflow, not in the app.

## 4. New package layout (this folder)

```
data/remoterules/
  RemoteRule.kt              // data class mirroring the published JSON schema
  RemoteRuleSet.kt           // { version, updatedAt, discardSenders, rules: List<RemoteRule> }
  RemoteRulesApi.kt          // Firebase Realtime Database / Firestore fetch of /rules
  RemoteRulesRepository.kt   // cache (DataStore), version check, merge into parse order
  RemoteRuleSyncWorker.kt    // WorkManager periodic (daily) + one-shot for the manual button
  Redactor.kt                // raw SMS -> redacted template (see §6)
  Submission.kt              // upload payload
  SubmissionRepository.kt    // writes to Firebase /submissions
```

## 5. Parsing engine changes

New tier order inside `SmsParser.parse`:

1. `BankTemplates.findMatch` (unchanged)
2. **`RemoteRulesRepository.tryMatch`** (new) — tries cached remote rules for this sender,
   ordered by `priority` descending, same discipline as `BankTemplates.findMatch`
   (try every matching rule before giving up, don't abort on the first sender match).
3. `LearnedPatternDao` per-device patterns (unchanged)
4. Review queue (unchanged)

Remote rules also carry `discardSenders` — merged into `SmsParser.ALWAYS_IGNORE_SENDERS`
at parse time, so a noisy sender you identify from one person's submissions (e.g. a new
NPS/mandate confirmation sender) silences it for everyone on the next sync, not just the
person who reported it.

Rationale for putting remote rules *before* local learned patterns: a remote rule has been
reviewed by you against real samples and is shared infrastructure; a local learned pattern
is a same-device guess PatternLearner made from one confirmation and should be superseded
the moment a better, reviewed rule exists for that sender.

## 6. Redaction — the part that matters most

`Redactor.redact(sender, body, confirmed: ParsedSms)` turns a raw SMS into a **template**,
never raw text, before anything is queued for upload. Same idea as `PatternLearner`'s
`generaliseLiteral`, reused/adapted here.

### 6.1 What gets masked

| Element | Rule | Example in → out |
|---|---|---|
| Amounts | replaced with `<AMT>` | `Rs.500.00` → `Rs.<AMT>` |
| Account/card numbers | keep only the last visible segment shape, zero the digits | `A/c XX1234` → `A/c XX<D4>`, `Card *5566` → `Card *<D4>` |
| Balances | full line/phrase dropped, not just the number | `Avl Bal Rs.15,342.50` → `Avl Bal <BAL>` |
| Dates / times | replaced with `<DATE>` / `<TIME>` | `29-09-26` → `<DATE>` |
| Reference / UTR numbers | replaced with `<REF>` | `Ref 123456789012` → `Ref <REF>` |
| Phone numbers | replaced with `<PHONE>` | `917036165000` → `<PHONE>` |
| VPA / UPI handles | keep the structure, mask the handle owner | `merchant@ybl` → `<VPA>` (merchant *name* text elsewhere is kept — see below) |
| Merchant / payee name | **kept** — this is what most rules need to anchor on, and it's already a payee name the user chose to transact with, not private banking data |
| Sender ID (`VM-FEDBNK`) | kept as-is | needed to route the rule to the right bank |

### 6.2 Worked example

Raw:
```
Rs.500.00 debited from A/c XX1234 to VPA merchant@ybl Ref 123456789012 on 29-09-26.
Avl Bal Rs.15,342.50
```

Redacted template uploaded:
```
Rs.<AMT> debited from A/c XX<D4> to VPA <VPA> Ref <REF> on <DATE>.
Avl Bal <BAL>
```

Nothing in the uploaded payload identifies the amount, the account, or the balance —
only the *shape* of the message, which is all a regex needs.

### 6.3 Submission payload

```json
{
  "template": "Rs.<AMT> debited from A/c XX<D4> to VPA <VPA> Ref <REF> on <DATE>.\nAvl Bal <BAL>",
  "sender": "VM-FEDBNK",
  "action": "review",
  "transactionType": "upi",
  "note": "UPI payment to a shop",
  "appVersion": "1.4.0",
  "submittedAt": "2026-09-29T03:20:00Z"
}
```

`action` is `"review"` (parser missed it, please write a rule) or `"discard"` (this
sender/shape is noise, please add it to `discardSenders`). No device or user identifier is
sent — submissions are anonymous by design; you don't need to know whose message it was to
write a rule for it.

## 7. Review-queue UI changes (`ui/review`)

Each `NEEDS_REVIEW` row in `ReviewQueueScreen` gets two new actions alongside the existing
manual-fix flow:

- **Send for review** — opens a small sheet: transaction-type chips (`UPI`, `NEFT/IMPS`,
  `Card`, `ATM`, `Auto-debit`, `Other`) and an optional one-line note. Confirms, redacts,
  uploads via `SubmissionRepository`, leaves the row in the queue (it's still unparsed
  locally until a rule comes back) but marks it `submitted` so it isn't re-prompted.
- **Discard** — reason chips (`OTP`, `Promo`, `Balance-only notice`, `Not a transaction`,
  `Other`). Uploads as `action: "discard"`, and locally marks the row `ParseStatus.IGNORED`
  immediately — the user doesn't need to wait for your review to stop seeing it.

## 8. Pulling rules

### 8.1 Automatic

- On app launch, at most once per 24h: `RemoteRulesRepository.syncIfDue()`.
- `RemoteRuleSyncWorker` also runs daily in the background (WorkManager, unmetered network
  not required — this payload is tiny).
- On a version bump, immediately re-run every `NEEDS_REVIEW` row (cap: most recent 200)
  against the new rule set, so a pull clears matching backlog without waiting for the next
  SMS to arrive.

### 8.2 Manual

Settings screen, new section "Rule updates":

```
Rule updates
  Current version: v42            (last checked 2 hours ago)
  [ Check now ]
```

Tapping "Check now" calls the same sync path synchronously and shows one of:
- "Already up to date (v42)"
- "Updated to v43 — 3 new rules. Re-checked 12 pending messages, cleared 5."

## 9. Firebase data shape

```
/rules                        (public read, admin-only write)
  version: 43
  updatedAt: "2026-09-29T03:00:00Z"
  discardSenders: ["NPSCRA", "PTNNPS", "AXISMF", "ITDCPC", "SOMEBANKPROMO"]
  rules: [
    {
      "id": "fedbnk_upi_debit_v1",
      "senders": ["FEDBNK"],
      "direction": "debit",
      "pattern": "Rs\\.?(?<amount>[\\d,.]+) debited from A/c (?<account>[X*\\d]+) to VPA (?<merchant>\\S+)",
      "fieldMap": { "amount": 1, "merchant": 2 },
      "priority": 10,
      "addedAt": "2026-09-20T00:00:00Z"
    }
  ]

/submissions/{autoId}          (write-only for clients, no client read; you read via console)
  template, sender, action, transactionType, note, appVersion, submittedAt
```

Security rules (Realtime Database, equivalent Firestore rules apply):

```json
{
  "rules": {
    "rules": { ".read": true, ".write": "auth != null && auth.token.email == 'desigrsujithnivel@gmail.com'" },
    "submissions": { ".read": "auth != null && auth.token.email == 'desigrsujithnivel@gmail.com'", ".write": true }
  }
}
```

Anonymous Firebase Auth (no login) is enough for the app to write submissions; only the
console needs a real signed-in identity, gated to your email.

## 10. Local caching

- Rule set cached via DataStore (`Preferences` or a small proto), keyed by `version`.
- Regex compilation happens once per sync, not per message — compiled `Regex` objects held
  in memory by `RemoteRulesRepository`, rebuilt only when the version changes.
- A rule whose `pattern` fails to compile (bad regex pushed by mistake) is skipped and
  logged, never crashes the parser — same defensive posture as `PatternLearner.applyToBody`.

## 11. Open questions for you to decide before implementation starts

1. Firebase Realtime Database vs. Firestore — RTDB is simpler for this shape (one small
   rules doc, an append-only submissions list); Firestore gives you query/grouping for free
   in the console. Recommendation: **Firestore**, since the console's main job is grouping
   submissions by template.
2. Anonymous auth for submissions, or fully open unauthenticated write with App Check to
   stop abuse? For a handful of trusted friends/family, anonymous auth is simplest.
3. Should "discard" submissions ever need your review, or can obvious ones (sender already
   in a common OTP/promo list) be filtered client-side before upload? Suggest: filter
   client-side against a small local blocklist first, only upload discards for senders not
   already known.

## 12. Milestones

- **M1** — Pull + apply: fetch `/rules`, cache, insert into parse order, manual "Check now"
  button. No submission yet — ships value (your hand-curated rules reach every phone)
  before the upload half exists.
- **M2** — Submission: redaction, the review/discard sheets, upload to `/submissions`.
- **M3** — Backlog re-parse on sync, submitted-state tracking so rows aren't re-prompted.
- **M4** — Web console (separate repo, see `expense-tracker-rules-console`).
