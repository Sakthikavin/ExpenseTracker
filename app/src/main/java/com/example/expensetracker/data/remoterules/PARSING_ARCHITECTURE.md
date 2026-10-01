# One rule list: no built-in templates, no transfers, no duplicate matching

Android-side spec. **Replaces `BANK_RULES_FIRST.md` §2** (the priority-5 split), which is
implemented but not released. §3 (`<CODE>`) and §4 (`BAL to` → `<PHONE>`) of that file stay.
Fold the result into `REQUIREMENTS.md` §5/§5.2 when you implement.

## 1. Why

Parsing has grown three layers that each read the same message: published rules, the in-app
`BankTemplates`, and code that runs after a match (reference, own-account transfer, account label,
merchant guessing). Rules on the console can't see or change the last two, the priority-5 split
exists only to order them, and a bank rule silently loses some of the after-match fields that
a template result would have had. Decided 2026-10-01:

- **Keep:** one ordered rule list; turning `1,66,421.00` into paise; the date read from the
  message; the review-queue heuristics.
- **Drop:** `BankTemplates`, transfer pairing, duplicate matching by reference, merchant guessing.
  Duplicates and own-account transfers are handled with ignore rules and discarded senders.

## 2. The pipeline

```
SMS → ALWAYS_IGNORE → discardSenders → ignoreRules
    → rules, highest priority first (published set, or the bundled default set before the first sync)
    → learned patterns → pre-notice → review heuristics → review / skipped
```

A matching rule produces exactly this, nothing computed elsewhere:

| `ParsedSms` field | From |
|---|---|
| `amountMinor` | the rule's `amount` group, via `parseAmountToMinorUnits` |
| `direction` | the rule's `direction` |
| `merchant` | the rule's `merchant` group, trimmed; `""` if not mapped |
| `accountLabel` | the rule's `account` group, as captured (`4821`); `""` if not mapped |
| `occurredAt` | `SmsDateParser.parse(body)` (unchanged) |

`ref` and `balance` stay valid fieldMap names (every mapped group must be non-empty, as now), but
their values aren't stored. Remove `referenceId` and `counterpartyAccount` from `ParsedSms`.

## 3. Any-sender rules: `senders: ["*"]`

A rule (or ignore rule) whose senders contain `"*"` applies to every sender. In
`RemoteRulesRepository.tryMatch` and `isIgnoredMessage`: `"*" in senders || normalized in senders`.
`discardSenders` never contains `"*"` (the console doesn't allow it; the app may ignore it).

Older app versions compare senders literally, so they never match `"*"`. That's safe: they still
have `BankTemplates`.

## 4. Bundled default rules: `app/src/main/assets/default_rules.json`

Already written (by the console side), in the published `RemoteRuleSet` shape, version 0. Seven
rules, all `senders: ["*"]`:

| id | priority | reads |
|---|---|---|
| `generic_block_debit_v1` | 6 | multi-line `Sent Rs.58.00` … `To Google India Digital Serv` (was `BlockFormat`) |
| `generic_block_scheme_debit_v1` | 5 | multi-line `Debit INR 292.00` … `APY/…` → merchant `APY` (was `BlockFormat` scheme line) |
| `generic_block_credit_v1` | 5 | multi-line `Received Rs.500.00` … `From ravi.k@ybl` |
| `generic_debit_amount_first_v1` | 4 | the old `android_debit_amount_first_v1`, any sender |
| `generic_debit_verb_first_v1` | 3 | the old `android_debit_verb_first_v1`, any sender |
| `generic_credit_amount_first_v1` | 2 | the old `android_credit_amount_first_v1`, any sender |
| `generic_credit_verb_first_v1` | 1 | the old `android_credit_verb_first_v1`, any sender |

The block rules use `(?im)` and `^…$` per line, so `To` must start a line: Axis's
`WhatsApp BAL to 917036165000` can't become the payee (the reason `BlockFormat` read lines).

- `RemoteRulesRepository`: before any successful sync (no cached set), load this asset as the
  rule set. A synced set replaces it entirely; the console publishes the same seven rules, and
  warns before publishing a version without a `"*"` rule.
- Refresh the asset from the live `/rules/current` before each release (one `curl` of the public
  document, converted; or keep this file and only update it when the generic rules change).

Checked on the console against the corpus in `RealMessages` (defaults alone, then with the live
v15 bank rules):

| message | today (templates) | defaults alone | with live bank rules |
|---|---|---|---|
| `axisApy` | debit 292.00, APY | same | same |
| `hdfcUpi` | debit 58.00, Google India Digital Serv | same | same |
| `federalUpi` | debit 1.00, KEERTHANA KU | same | same |
| `npsCredit` | credit 5,000.00, SAKTHI KAVIN S S | same | same |
| `npsDebit` | debit 5,000.00, **NPS Contribution** | debit 5,000.00, **HDFC Bank XX3941** | same as defaults |
| `tmbCredit` / `tmbDebit` | (bank rules) | unparsed | `tmbank_credit_v1` / `tmbank_debited_with_v1` |
| `canaraDebit` (`Dr.`) | review | review | review |

The one regression is the NPS merchant (the remark guesser is gone). It's a bank-rule job: an
HDFCBK rule for `debited from HDFC Bank XX… Info: NEFT Dr-…-(?<merchant>[^.-]+?)\s*\w?\. Avl bal`.

## 5. Remove

| What | Where |
|---|---|
| Templates | `data/sms/BankTemplates.kt` (keep `parseAmountToMinorUnits` and the review heuristics, see below), `BankTemplatesTest.kt` |
| Priority split | `tryBankRules` / `tryFallbackRules`, `BANK_RULE_MIN_PRIORITY`, `RulePriorityOrderTest.kt`; back to one `tryMatch` |
| Duplicate matching | `SmsRepository.reconcileWithExisting` and its call; `ParsedSms.referenceId`; `SmsReferenceParser.primaryReference` |
| Transfer pairing | `TransferMatcher.kt`, `TransferRepository.kt`, `TransferMatcherTest.kt`, `ParsedSms.counterpartyAccount`, transfer rows in `TransactionsScreen` / `TransactionsViewModel`, the transfer queries in `TransactionDao` |
| Own accounts | `OwnAccountEntity`, `OwnAccountDao`, `ui/accounts/*`, `Destination.Accounts` and its nav icon, `AccountsViewModelTest.kt` |

**Keep, moved:** `looksFinancial`, `mentionsAmount`, `looksLikePreNotice`, `looseAmountMinor` and
`parseAmountToMinorUnits` into `data/sms/ReviewHeuristics.kt` (and `AmountParser.kt` if you prefer).
`hasBankAlertMarker` uses `SmsReferenceParser.referencesIn`; keep that one function (or inline its
regex) and delete the rest of `SmsReferenceParser`.

## 6. Database: version 7 → 8

- `DROP TABLE own_accounts` (or whatever `OwnAccountEntity`'s table is).
- `UPDATE transactions SET transferGroupId = NULL`. Keep the `referenceId` and `transferGroupId`
  columns for now: dropping a column in SQLite means rebuilding the table, which isn't worth it
  for two nullable columns nothing reads. Remove them from the entity in a later migration if you
  want them gone.
- `MigrationTest`: 7 → 8 keeps every transaction, empties `transferGroupId`.

**Effect on existing data:** transfers paired so far become a plain debit and a plain credit, so
those months' spending and income both rise by the transfer amounts. Delete one leg, or both, if
it matters.

## 7. Duplicates and own-account transfers from now on

- Two alerts for one payment (the bank's and a UPI app's): discard the app's sender on the
  console, or an ignore rule for its "paid" message.
- A transfer to your own account: an ignore rule on its wording. Published rules reach every phone
  and the rules document is publicly readable, so don't put account digits in the pattern.

## 8. Tests

- `RealMessageTest`: parse with `SmsParser` backed by the bundled `default_rules.json` (plus
  the TMB rules where the test needs them); expectations as in the §4 table, `npsDebit`'s merchant
  updated.
- `RemoteRulesRepositoryTest`: `"*"` matches any sender; a bank rule at 10 beats a `"*"` rule at 4;
  no cached set → the asset's rules are used.
- `SmsRepositoryTest`: a message read twice (bank + app) is two transactions (the documented
  behaviour now); nothing references transfers.

## 9. Rollout

1. Console: publish the seven generic rules from `default_rules.json` (replacing the four
   `android_*` rules). Old app versions ignore `"*"` rules and keep using their templates.
2. App: §2–§6, release.
3. On the phone: Settings → Check now, then Import SMS history if you want old messages re-read
   under the new list.

## 10. Checklist

- [ ] §2 `applyRule` fills `accountLabel` from `account`; `ParsedSms` loses `referenceId`,
      `counterpartyAccount`.
- [ ] §3 `"*"` senders in `tryMatch` and `isIgnoredMessage`.
- [ ] §4 bundled defaults used before the first sync.
- [ ] §5 removals; review heuristics moved.
- [ ] §6 migration 7 → 8 + `MigrationTest`.
- [ ] §8 tests.
- [ ] REQUIREMENTS.md §5/§5.2; mark `BANK_RULES_FIRST.md` §2 superseded.
