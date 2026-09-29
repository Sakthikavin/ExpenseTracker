# Ignore rules (remote "not a transaction" patterns)

Android-side spec for a feature the rules console ships first. Companion to `REQUIREMENTS.md` in
this folder; fold the schema and order changes below into its §5, §5.2, §6.1, §9 and §10 when you
implement, so the two repos' specs stay identical.

## 1. Why

Some bank SMS look like transactions but aren't. The first one reported:

```
TXN DECLINED: Rs.<AMT> on <DATE> at <TIME> on HDFC Bank Debit Card xx<D4>. Reason: Online set
Limit Exceeded. Modify:https://1.hdfc.bank.in/HDFCBK/s/a/E0WMgeP0
```

Today the only remote tool is `discardSenders`, which silences a whole sender. That would hide
HDFC's real spends too. Worse, a built-in template or remote rule might read "Rs.500 … HDFC Bank
Debit Card" as a real ₹500 spend. Ignore rules let the console publish "this *kind of message*
from this sender is noise" and have every phone drop it on the next sync.

## 2. Schema: `ignoreRules` in `/rules/current`

A new top-level array next to `discardSenders`:

```
/rules/current
  version: 44
  updatedAt: "..."
  discardSenders: [...]
  rules: [...]
  ignoreRules: [
    {
      "id": "hdfcbk_txn_declined_v1",
      "senders": ["HDFCBK"],
      "pattern": "^TXN DECLINED: ",
      "reason": "Declined transaction",
      "addedAt": "2026-09-29T00:00:00Z"
    }
  ]
```

| Field | Type | Notes |
|---|---|---|
| `id` | string | Unique across `rules` and `ignoreRules`. |
| `senders` | string[] | At least one, in `PatternLearner.normaliseSender` form (§5.1). No "any sender" rules. |
| `pattern` | string | `java.util.regex`, same dialect and compile rules as `rules[].pattern` (§10.1). |
| `reason` | string | Short label for humans (≤ 40 chars). The app may log it but doesn't need it. |
| `addedAt` | string | ISO timestamp, informational. |

No `fieldMap`, `direction` or `priority`: an ignore rule only answers "is this noise?".

The field may be missing (every rule set published before this feature) and must then be treated
as an empty list.

## 3. Where it runs in `SmsParser.parse`

Right after the two existing sender-level noise checks and **before any parsing tier**:

1. `ALWAYS_IGNORE_SENDERS` → `IgnoredAsNoise` (unchanged)
2. `discardSenders` → `IgnoredAsNoise` (unchanged)
3. **New: `RemoteRulesRepository.isIgnoredMessage(sender, body)` → `IgnoredAsNoise`**
4. `BankTemplates.findMatch` (unchanged)
5. `RemoteRulesRepository.tryMatch` (unchanged)
6. Learned patterns, pre-notice filter, `looksFinancial`, small-amount filter, review queue (unchanged)

It must come before step 4. Otherwise a built-in template can still book the declined payment.

### 3.1 Matching

`isIgnoredMessage(sender, body)` is true when some ignore rule has
`normaliseSender(sender) in rule.senders` **and** `rule.regex.find(body) != null`.

- `find`, not `matches`: the pattern may match anywhere, same as `tryMatch`.
- No capture groups are read; any match is enough.
- Order doesn't matter (every match means the same outcome).
- A pattern that fails to compile is skipped and logged, never a crash and never a match (same as
  §10 for `rules`).
- Compile once per sync alongside `rules`, not per message.

The console predicts the effect of publishing (its impact check). Its prediction assumes exactly
this order and matching, so keep them identical.

## 4. Parsing and caching

- `RemoteRuleSet` gets `ignoreRules: List<RemoteIgnoreRule>` with
  `data class RemoteIgnoreRule(val id: String, val senders: List<String>, val pattern: String, val reason: String)`.
- `RemoteRulesApi.parseDocument`: read `ignoreRules` with the same `fsArray` / `mapValue` helpers
  as `rules`. Missing field → `emptyList()`. An entry missing `id`, `senders` or `pattern` is
  dropped, like `parseRule` returning null.
- `RemoteRulesRepository.saveToDisk` / load from disk: write `ignoreRules`, and **read it with
  `optJSONArray`**, not `getJSONArray`. A cache written by the current app version has no
  `ignoreRules` key, and `getJSONArray` would throw on first launch after the update.
- `setCache`: compile `ignoreRules` next to `rules` (`CompiledIgnoreRule(rule, regex)`).

## 5. Redaction: mask URLs (§6.1)

The template above still carries `https://1.hdfc.bank.in/HDFCBK/s/a/E0WMgeP0`. Bank short-link
codes are often unique per customer, so they can identify the person. The current check only
refuses 5+ digit runs and `x@y` handles, so this one went through.

- `Redactor`: add a rule that replaces URLs with `<URL>`, and run it **first**, before the
  date/time/ref/phone rules, so digits inside a URL aren't half-masked into `<DATE>` fragments.
  Suggested pattern: `(?i)\b(?:https?://|www\.)\S+`, plus bare `domain.tld/path` links such as
  `1.hdfc.bank.in/HDFCBK/s/a/…`: `(?i)\b[a-z0-9-]+(?:\.[a-z0-9-]+)+/\S*`.
- §6.1.1 check: also refuse a template that still contains `http`, `www.` or a `domain.tld/`
  shape outside placeholders.
- Tests: the HDFC declined SMS above redacts to
  `… Modify:<URL>`, and the check passes; a template with a raw URL fails the check.

The console will flag URLs in submissions as "possible unredacted data" and knows `<URL>` as a
placeholder.

## 6. Also bring into the spec (already true in code)

§5.2 step 3 says a rule wins when every `fieldMap` group is non-empty. `applyRule` also requires
the `amount` capture to parse via `parseAmountToMinorUnits`, and falls through otherwise. The
console now checks the same thing, so write it into §5.2 step 3.

## 7. Tests to add

- `SmsParser`: an ignore rule for `HDFCBK` with `^TXN DECLINED: ` returns `IgnoredAsNoise` for the
  declined SMS, **even when a built-in template or remote rule would have parsed it**.
- Same rule, a normal HDFCBK spend SMS still parses.
- Same rule, the declined text from a different sender is not ignored by it.
- Sender normalization: `BP-HDFCBK-S` is matched by `senders: ["HDFCBK"]`.
- A bad ignore pattern (e.g. `(`) is skipped without throwing.
- `RemoteRulesApi`: a document without `ignoreRules` parses to an empty list; one with a malformed
  entry drops only that entry.
- Disk cache: loading a cache JSON with no `ignoreRules` key works.

## 8. Rollout

1. Console ships first. It can publish `ignoreRules` safely before this app update: the current
   app reads Firestore fields by name and ignores unknown ones, and `firestore.rules` doesn't
   restrict the fields of `/rules/current`. Current phones just don't apply them yet.
2. This app update makes phones apply them from the next rules sync.
3. Messages already sitting in a review queue aren't re-checked; that's the M3 backlog re-parse
   (§8.1), not this feature. When M3 lands, the re-check must apply ignore rules too
   (`NEEDS_REVIEW` → `IGNORED`).
4. Submissions: nothing changes. People keep using **Discard** (`action: "discard"`), which is
   what the console turns into ignore rules.
