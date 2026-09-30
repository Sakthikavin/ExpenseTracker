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

1. `ALWAYS_IGNORE_SENDERS` / `discardSenders` (unchanged)
2. **`RemoteRulesRepository.isIgnoredMessage`** (new, see `IGNORE_RULES.md`) — a published
   sender+pattern pair saying this *kind* of message isn't a transaction (a declined-payment
   alert, say). Runs before any parsing tier below, so a built-in template or remote rule can't
   still book the non-payment it describes.
3. `BankTemplates.findMatch` (unchanged)
4. **`RemoteRulesRepository.tryMatch`** (new) — tries cached remote rules for this sender,
   ordered by `priority` descending, same discipline as `BankTemplates.findMatch`
   (try every matching rule before giving up, don't abort on the first sender match).
5. `LearnedPatternDao` per-device patterns (unchanged)
6. Review queue (unchanged)

Remote rules also carry `discardSenders` — merged into `SmsParser.ALWAYS_IGNORE_SENDERS`
at parse time, so a noisy sender you identify from one person's submissions (e.g. a new
NPS/mandate confirmation sender) silences it for everyone on the next sync, not just the
person who reported it.

### 5.1 Sender normalization

A rule's `senders` and `discardSenders` are stored in `PatternLearner.normaliseSender` form
(`VM-FEDBNK` → `FEDBNK`), matching how the console groups submissions. Both `tryMatch` and the
discard check must run the incoming SMS sender through `normaliseSender` before comparing —
never compare against the raw header.

### 5.2 How a rule is picked (must match the console's prediction)

The console predicts which submission groups a rule-set change gains or loses, so the on-device
selection has to follow this exact order:

1. `normaliseSender(sender)` in `discardSenders` → treat as noise, skip remote-rule matching
   entirely.
2. `RemoteRulesRepository.isIgnoredMessage(sender, body)` (`IGNORE_RULES.md` §3): any
   `ignoreRules` entry whose `senders` contain the normalized sender and whose `pattern` matches
   anywhere in the body → treat as noise. No `fieldMap` to satisfy, no `priority`; any match is
   enough and order among ignore rules doesn't matter.
3. Otherwise, walk rules whose `senders` contain the normalized sender, by `priority` descending
   (ties keep list order as published).
4. The first rule that matches the body **and** yields a non-empty value for every `fieldMap`
   group wins; a rule missing a mapped field falls through to the next candidate.
5. Rules (and ignore rules) that fail to compile (§10) are skipped, never considered a match.

Rationale for putting remote rules *before* local learned patterns: a remote rule has been
reviewed by you against real samples and is shared infrastructure; a local learned pattern
is a same-device guess PatternLearner made from one confirmation and should be superseded
the moment a better, reviewed rule exists for that sender.

## 6. Redaction — the part that matters most

`Redactor.redact(sender, body, confirmed: ParsedSms)` turns a raw SMS into a **template**,
never raw text, before anything is queued for upload. Same idea as `PatternLearner`'s
`generaliseLiteral`, reused/adapted here.

### 6.1 What gets masked

**The invariant:** a template differs from its message *only* where a value became a placeholder.
Currency words, separators, spacing and digit counts are message *shape*, not personal data, and
every rule depends on them — a template that drops any of it shows rule authors a message that
doesn't exist. `RedactorInvariantTest` enforces this by fitting each template back over the real
message it came from.

| Element | Rule | Example in → out |
|---|---|---|
| Amounts | replaced with `<AMT>` | `Rs.500.00` → `Rs.<AMT>` |
| Account/card numbers | keep the bank's own masking and the gap after it, replace the digits with `<D`*n*`>` for the *n* digits masked | `A/c XX1234` → `A/c XX<D4>`, `Card *5566` → `Card *<D4>`, `XX 12345` → `XX <D5>` |
| Balances | only the number goes; the phrase, the currency token and its spacing stay | `Avl Bal Rs.15,342.50` → `Avl Bal Rs.<BAL>`, `Avl Bal:INR 1,234` → `Avl Bal:INR <BAL>` |
| Dates / times | replaced with `<DATE>` / `<TIME>`, or `<DATEW>` when the date contains whitespace (a rule's `\S+` date group can't read one) | `29-09-26` → `<DATE>`, `30 Sep 2026` → `<DATEW>` |
| Reference / UTR numbers | replaced with `<REF>` | `Ref 123456789012` → `Ref <REF>` |
| Phone numbers | replaced with `<PHONE>`, but only for shapes that really are one — an Indian mobile with optional country code, or a `1800` helpline. A looser rule called any 10–12 digit run a phone number, including mandate ids | `917036165000` → `<PHONE>`, `18002586161` → `<PHONE>` |
| Any other long digit run | replaced with `<NUM>` as a last resort, after every rule above: a mandate id, a customer id, a reference hyphen-joined into a narration with no label to key on. Shares the §6.1.1 threshold, so redaction can't produce a template the app then refuses to upload. A constant prefix stays, being shape and a usable anchor | `APY/500405010905/920010018` → `APY/<NUM>/<NUM>`, `MUM-HDFCH00842011992-NET` → `MUM-HDFCH<NUM>-NET` |
| VPA / UPI handles | keep the structure, mask the handle owner | `merchant@ybl` → `<VPA>` (merchant *name* text elsewhere is kept — see below) |
| URLs / bank short links | replaced with `<URL>`, run **first** (before date/time/ref) so digits in a link aren't half-masked into `<DATE>`/`<REF>` fragments | `Modify:https://1.hdfc.bank.in/HDFCBK/s/a/E0WMgeP0` → `Modify:<URL>` |
| Merchant / payee name | **kept** — this is what most rules need to anchor on, and it's already a payee name the user chose to transact with, not private banking data |
| Sender ID (`VM-FEDBNK`) | kept as-is | needed to route the rule to the right bank |

### 6.1.1 Redaction check

Before upload, `Redactor` must flag (and the app must refuse to send) any template that still
contains 5+ consecutive digits, an `x@y`-shaped handle, or a raw URL/bare-domain link (`http`,
`www.`, or a `domain.tld/`-shaped run) outside the `<...>` placeholders above — same rule the
console applies to inbound submissions. Add this as a unit test alongside the existing `Redactor`
tests, covering a stray digit run, a stray handle, and a stray link.

### 6.2 Worked example

Raw:
```
Rs.500.00 debited from A/c XX1234 to VPA merchant@ybl Ref 123456789012 on 29-09-26.
Avl Bal Rs.15,342.50
```

Redacted template uploaded:
```
Rs.<AMT> debited from A/c XX<D4> to VPA <VPA> Ref <REF> on <DATE>.
Avl Bal Rs.<BAL>
```

Nothing in the uploaded payload identifies the amount, the account, or the balance —
only the *shape* of the message, which is all a regex needs. `Rs.` stays because it is shape: the
number is what identifies someone, the word for the currency isn't.

### 6.3 Submission payload

`firestore.rules` rejects anything outside this shape, so the client must send exactly:

| Field             | Required                  | Type / limit                                  |
|-------------------|----------------------------|------------------------------------------------|
| `template`        | yes                       | string, 1–2000                                |
| `sender`          | yes                       | string, 1–40 (raw header is fine)             |
| `action`          | yes                       | `"review"` or `"discard"`                     |
| `submittedAt`     | yes                       | ISO-8601 string, ≤ 40                         |
| `transactionType` | no                        | string, ≤ 30                                  |
| `note`            | no                        | string, ≤ 200                                 |
| `appVersion`      | no                        | string, ≤ 30                                  |
| `rulesVersion`    | no (send it)              | int ≥ 0, rule-set version loaded on the phone |

```json
{
  "template": "Rs.<AMT> debited from A/c XX<D4> to VPA <VPA> Ref <REF> on <DATE>.\nAvl Bal <BAL>",
  "sender": "VM-FEDBNK",
  "action": "review",
  "transactionType": "upi",
  "note": "UPI payment to a shop",
  "appVersion": "1.4.0",
  "rulesVersion": 42,
  "submittedAt": "2026-09-29T03:20:00Z"
}
```

`action` is `"review"` (parser missed it, please write a rule) or `"discard"` (this
sender/shape is noise, please add it to `discardSenders`). No device or user identifier is
sent — submissions are anonymous by design; you don't need to know whose message it was to
write a rule for it. `rulesVersion` lets a resolved group reopen only when a phone that already
has the fix still fails to parse. If this payload shape ever changes, update the console's
`firestore.rules` in the same change.

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
- On a version bump, immediately re-run **every** `NEEDS_REVIEW` row against the new rule set, so
  a pull clears matching backlog without waiting for the next SMS to arrive. No cap: a capped
  re-parse leaves the oldest rows of a long queue permanently stuck, because nothing ever re-reads
  them and so no rule published later can reach them. Walked newest-first in pages by keyset, not
  `OFFSET` — rows leave the status as they clear, and an offset would then step over that many
  rows it never examined.

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

### 8.3 Device status

On each rules sync and app launch, write `/devices/{uid}` (`uid` = the phone's anonymous-auth
uid; a phone can only write its own doc, enforced by `firestore.rules`):

| Field           | Required | Type / limit                              |
|-----------------|----------|--------------------------------------------|
| `rulesVersion`  | yes      | int ≥ 0                                   |
| `lastSeen`      | yes      | ISO-8601 string, ≤ 40                     |
| `lastRulesSync` | no       | ISO-8601 string, ≤ 40                     |
| `appVersion`    | no       | string, ≤ 30                              |
| `model`         | no       | string, ≤ 60                              |
| `label`         | no       | string, ≤ 40, user-set name (e.g. "Amma") |

Submissions never carry this uid, so a device can't be linked to what it submitted — the console's
devices page and the submissions inbox stay unlinkable by design. This settles §11 Q2 in favour of
**anonymous auth**.

## 9. Firebase data shape

**Decided: Firestore** (not Realtime Database) — the console's main job is grouping
submissions by template, which Firestore's queries give for free (§11 Q1).

The live rule set is a single document, `/rules/current` — Firestore needs a doc id, so the
collection is `rules` but the app only ever fetches the `current` doc. `RemoteRulesApi` must read
`rules/current`, not the `rules` collection.

```
/rules/current                 (public read, admin-only write)
  version: 43
  updatedAt: "2026-09-29T03:00:00Z"
  discardSenders: ["NPSCRA", "PTNNPS", "AXISMF", "ITDCPC", "SOMEBANKPROMO"]
  rules: [
    {
      "id": "fedbnk_upi_debit_v1",
      "senders": ["FEDBNK"],
      "direction": "debit",
      "pattern": "Rs\\.?(?<amount>[\\d,.]+) debited from A/c (?<account>[X*\\d]+) to VPA (?<merchant>\\S+)",
      "fieldMap": { "amount": 1, "account": 2, "merchant": 3 },
      "priority": 10,
      "addedAt": "2026-09-20T00:00:00Z"
    }
  ]
  ignoreRules: [
    {
      "id": "hdfcbk_txn_declined_v1",
      "senders": ["HDFCBK"],
      "pattern": "^TXN DECLINED: ",
      "reason": "Declined transaction",
      "addedAt": "2026-09-29T00:00:00Z"
    }
  ]

/submissions/{autoId}          (write-only for clients, no client read; you read via console)
  template, sender, action, transactionType, note, appVersion, rulesVersion, submittedAt

/devices/{uid}                 (see §8.3; console-only read)
/rules_history/{version}       (console-only, snapshot of each published rule set)
/groupStates/{groupId}         (console-only, tracks reviewer state per submission group)
```

`fieldMap` values are capture-group **numbers**, not named-group lookups — count every capturing
group left-to-right, unnamed ones included (`(?:…)` doesn't count). In the pattern above, group 1
is `amount`, group 2 is `account`, group 3 is `merchant`.

`ignoreRules` (see `IGNORE_RULES.md` for the full spec) has no `fieldMap`, `direction` or
`priority` — it only answers whether a message is noise, so a matching entry is enough on its
own. The field is absent from every rule set published before it existed and must then be
treated as an empty list, both over the wire and in the on-disk cache (§10).

Security rules are Firestore, not Realtime Database. The real rules live in the console repo's
`firestore.rules` — keep this spec in sync with it, don't hand-copy a stale snippet:

```
function isAdmin() {
  return request.auth != null
    && request.auth.token.email == 'sakthikavincit@gmail.com'
    && request.auth.token.email_verified;
}

match /rules/{doc} {
  allow read: if true;
  allow write: if isAdmin();
}

match /submissions/{doc} {
  allow create: if isValidSubmission(request.resource.data);   // §6.3 payload shape
  allow read, update, delete: if isAdmin();
}

match /devices/{uid} {
  allow read, delete: if isAdmin();
  allow create, update: if request.auth != null && request.auth.uid == uid
    && isValidDevice(request.resource.data);                    // §8.3 payload shape
}
```

Anonymous Firebase Auth (no login) is enough for the app to write submissions and its own
`/devices/{uid}` doc; only the console needs a real signed-in identity, gated to the admin email
above.

## 10. Local caching

- Rule set cached via DataStore (`Preferences` or a small proto), keyed by `version`.
- Regex compilation happens once per sync, not per message — compiled `Regex` objects held
  in memory by `RemoteRulesRepository`, rebuilt only when the version changes. `ignoreRules`
  compile alongside `rules` on the same cadence, not on their own schedule.
- A rule whose `pattern` fails to compile (bad regex pushed by mistake) is skipped and
  logged, never crashes the parser — same defensive posture as `PatternLearner.applyToBody`.
  `ignoreRules` entries get the same treatment, and one missing `id`/`senders`/`pattern` is
  dropped on parse rather than failing the whole document.
- The disk cache must read `ignoreRules` with `optJSONArray`, not `getJSONArray`: a cache
  written before this feature existed has no such key, and the first launch after the update
  must not throw on it.

### 10.1 Regex dialect

Patterns are validated against `java.util.regex` by the console, so the app must compile them
the same way:

- Compile with plain `Regex(pattern)` — **no extra `RegexOption`s** — so inline flags in the
  pattern take effect as written.
- A leading `(?i)` is allowed and is how the console writes case-insensitive rules; don't strip
  or reinterpret it.
- The console refuses to publish patterns Java can't handle, so a compiled rule should never hit
  these, but the app's compile-failure handling above must still catch them defensively: group
  names with non-alphanumeric characters (e.g. `merchant_name`), `*`/`+` inside a lookbehind, and
  `\p{…}` Unicode property classes.

## 11. Open questions for you to decide before implementation starts

1. ~~Firebase Realtime Database vs. Firestore~~ — **Decided: Firestore** (§9). The rules doc
   lives at `rules/current`; console-only collections are `rules_history`, `groupStates`, and
   `devices`.
2. ~~Anonymous auth for submissions, or fully open unauthenticated write with App Check?~~ —
   **Decided: anonymous auth** (§8.3). Every install writes its own `/devices/{uid}` doc, keyed
   by its anonymous-auth uid; submissions never carry that uid, so a device can't be linked to
   what it submitted.
3. Should "discard" submissions ever need your review, or can obvious ones (sender already
   in a common OTP/promo list) be filtered client-side before upload? Suggest: filter
   client-side against a small local blocklist first, only upload discards for senders not
   already known. Still open.

## 12. Milestones

- **M1** — Pull + apply: fetch `rules/current`, cache, insert into parse order, manual
  "Check now" button. No submission yet — ships value (your hand-curated rules reach every
  phone) before the upload half exists.
- **M2** — Submission: redaction, the review/discard sheets, upload to `/submissions`. **Built.**
  `/submissions` allows an unauthenticated create (see the console's `firestore.rules`), so the
  upload is a plain REST POST — anonymous auth is only needed for §8.3's `/devices/{uid}` write,
  which hasn't shipped yet.
- **M3** — Backlog re-parse on sync, and submitted-state tracking so rows aren't re-prompted.
  **Built.** `RuleSyncCoordinator` re-parses the queue on a version bump (cap 200, most recent
  first), and `raw_sms.submittedAt` (schema v6) records that a template already went off for a
  rule.
- **M4** — Web console (separate repo, `expense-tracker-rules-console`). **Built.** Covers:
  inbox, rule editor/tester, rules page, publish with diff and impact check, history/rollback,
  and a devices page that shows sample data until §8.3 ships on the Android side.
