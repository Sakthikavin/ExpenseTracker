# Changes needed in the Android spec

For `ExpenseTracker/app/src/main/java/com/example/expensetracker/data/remoterules/REQUIREMENTS.md`.
The rules console already relies on each of these, so the Android implementation must match.

## 1. Owner email (§9)

The admin account is now `sakthikavincit@gmail.com`. The spec's security rules still say
`desigrsujithnivel@gmail.com`; replace it. The real rules live in the console repo's
`firestore.rules`.

## 2. Firestore, and where the rule set lives (§9, §11 Q1)

- Decided: **Firestore** (not Realtime Database).
- The live rule set is the doc **`/rules/current`** (Firestore needs a doc id). `RemoteRulesApi`
  must read `rules/current`.
- Console-only collections: `/rules_history/{version}`, `/groupStates/{groupId}`. Also
  `/devices/{uid}` (section 8).

## 3. `fieldMap` example is wrong (§9)

`fieldMap` values are capture-group **numbers**, and unnamed groups count too. In the example
pattern group 2 is `account`, so: `"fieldMap": { "amount": 1, "account": 2, "merchant": 3 }`

## 4. Sender names are stored normalized (§5)

A rule's `senders` and `discardSenders` use `PatternLearner.normaliseSender` form (`VM-FEDBNK` →
`FEDBNK`).
`tryMatch` and the discard check must compare `normaliseSender(sender)`, never the raw header.

## 5. How a phone picks a rule (§5)

The console predicts which submission groups a new rule set gains or loses, assuming this order:

1. `normaliseSender(sender)` in `discardSenders` → ignore as noise.
2. Otherwise, rules whose `senders` contain the normalized sender, by `priority` descending (ties
   keep list order).
3. The first rule that matches **and** yields a non-empty value for every `fieldMap` group wins; a
   rule missing a mapped field falls through.
4. Rules that don't compile are skipped.

## 6. Regex dialect (§10)

- Patterns are `java.util.regex`. Compile with plain `Regex(pattern)` and **no extra options**, so
  inline flags work.
- A leading `(?i)` is allowed; it's how case-insensitive rules are written.
- The console refuses patterns Java can't handle: group names with non-alphanumerics (
  `merchant_name`), `*`/`+` inside a lookbehind, `\p{…}`.

## 7. Submission payload (§6.3)

`firestore.rules` rejects anything else. Send exactly:

| Field             | Required                  | Type / limit                                  |
|-------------------|---------------------------|-----------------------------------------------|
| `template`        | yes                       | string, 1–2000                                |
| `sender`          | yes                       | string, 1–40 (raw header is fine)             |
| `action`          | yes                       | `"review"` or `"discard"`                     |
| `submittedAt`     | yes                       | ISO-8601 string, ≤ 40                         |
| `transactionType` | no                        | string, ≤ 30                                  |
| `note`            | no                        | string, ≤ 200                                 |
| `appVersion`      | no                        | string, ≤ 30                                  |
| `rulesVersion`    | no (**new**, please send) | int ≥ 0, rule-set version loaded on the phone |

`rulesVersion` lets a resolved group reopen only when a phone that already has the fix still fails.
If the payload changes, update the console's `firestore.rules` in the same change.

## 8. New: device status

On each rules sync and app launch, write `/devices/{uid}` (`uid` = anonymous-auth uid; a phone can
only write its own doc):

| Field           | Required | Type / limit                              |
|-----------------|----------|-------------------------------------------|
| `rulesVersion`  | yes      | int ≥ 0                                   |
| `lastSeen`      | yes      | ISO-8601 string                           |
| `lastRulesSync` | no       | ISO-8601 string                           |
| `appVersion`    | no       | string, ≤ 30                              |
| `model`         | no       | string, ≤ 60                              |
| `label`         | no       | string, ≤ 40, user-set name (e.g. "Amma") |

Never put the uid in a submission, so a device can't be linked to what it submitted.
This settles §11 Q2 in favour of **anonymous auth**.

## 9. Redaction check

The console flags templates with 5+ consecutive digits or an `x@y` handle outside placeholders.
Add the same check as a `Redactor` unit test.

## 10. Milestones (§12)

M4 (web console) is built: inbox, rule editor/tester, rules page, publish with diff and impact
check,
history/rollback, and a devices page that shows sample data until section 8 ships.
