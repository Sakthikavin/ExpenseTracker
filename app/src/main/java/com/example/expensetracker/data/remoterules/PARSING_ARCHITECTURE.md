# One rule list from the console: no built-in templates, no transfers, no duplicate matching

Android-side spec. **Replaces `BANK_RULES_FIRST.md` §2** (the priority-5 split), which is
implemented but not released. §3 (`<CODE>`) and §4 (`BAL to` → `<PHONE>`) of that file stay.
Fold the result into `REQUIREMENTS.md` §5/§5.2 when you implement.

## 1. Why

Parsing has grown three layers that each read the same message: published rules, the in-app
`BankTemplates`, and code that runs after a match (reference, own-account transfer, account label,
merchant guessing). Rules on the console can't see or change the last two, the priority-5 split
exists only to order them, and a bank rule silently loses some of the after-match fields that
a template result would have had. Decided 2026-10-01:

- **Keep:** one ordered rule list, synced from the console and nowhere else (no rules ship in the
  app); turning `1,66,421.00` into paise; the date read from the message; the review-queue
  heuristics.
- **Drop:** `BankTemplates`, transfer pairing, duplicate matching by reference, merchant guessing.
  Duplicates and own-account transfers are handled with ignore rules and discarded senders.

## 2. The pipeline

```
SMS → ALWAYS_IGNORE → discardSenders → ignoreRules
    → rules, highest priority first (the cached published set; none before the first sync)
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

## 4. Rules only from the console; the generic ones are ordinary published rules

No rules ship in the app. The four generic regexes `BankTemplates` used, and `BlockFormat`'s
multi-line reading, become published rules with `senders: ["*"]` (the console keeps them in
`data/generic-rules.json` for a one-time import):

| id | priority | reads |
|---|---|---|
| `generic_block_debit_v1` | 6 | multi-line `Sent Rs.58.00` … `To Google India Digital Serv` (was `BlockFormat`) |
| `generic_block_scheme_debit_v1` | 5 | multi-line `Debit INR 292.00` … `APY/…` → merchant `APY` (was `BlockFormat` scheme line) |
| `generic_block_credit_v1` | 5 | multi-line `Received Rs.500.00` … `From ravi.k@ybl` |
| `generic_debit_amount_first_v2` | 4 | the old `android_debit_amount_first_v1`, any sender |
| `generic_debit_verb_first_v2` | 3 | the old `android_debit_verb_first_v1`, any sender |
| `generic_credit_amount_first_v2` | 2 | the old `android_credit_amount_first_v1`, any sender |
| `generic_credit_verb_first_v2` | 1 | the old `android_credit_verb_first_v1`, any sender |

**Published as v16 (2026-10-01).** The four single-line rules are `_v2`: the old regexes with a
better merchant ending. They skip a leading `VPA `, don't start at "your A/c" or "card", stop at
` No `, and only end at `.`/`,` when it isn't inside a handle. So `landlord.ravi@ybl` stays whole
instead of `VPA landlord`, and `VPA kumarstores@okaxis No 427381920113` becomes `kumarstores@okaxis`.

The block rules use `(?im)` and `^…$` per line, so `To` must start a line: Axis's
`WhatsApp BAL to 917036165000` can't become the payee (the reason `BlockFormat` read lines).

**Before the first sync** the phone has no rules: every financial-looking message goes to review
(or skipped), as if nothing matched. The first successful sync re-checks the whole review queue
and the skipped messages (`reparseNeedsReview`, already in this build), so they clear without
anything else. After that the cached set is used offline, as now.

- **Import SMS history** syncs first: run `ruleSyncCoordinator.sync()` (or use the cached set if
  there is one), then import. If there's no cached set and the sync fails, say so before importing:
  "Couldn't load rules. Messages will go to review and be re-checked on the next sync."
- `RemoteRulesRepository` with no cached set: `tryMatch` returns null, `isIgnoredMessage` false,
  `isDiscardedSender` false (only `ALWAYS_IGNORE_SENDERS` applies). No fallback set.

Checked on the console against the corpus in `RealMessages` (generic rules alone, then with the
live v15 bank rules):

| message | today (templates) | generic rules alone | with live bank rules |
|---|---|---|---|
| `axisApy` | debit 292.00, APY | same | same |
| `hdfcUpi` | debit 58.00, Google India Digital Serv | same | same |
| `federalUpi` | debit 1.00, KEERTHANA KU | same | same |
| `npsCredit` | credit 5,000.00, SAKTHI KAVIN S S | same | same |
| `npsDebit` | debit 5,000.00, **NPS Contribution** | debit 5,000.00, **HDFC Bank XX3941** | same as alone |
| `tmbCredit` / `tmbDebit` | (bank rules) | unparsed | `tmbank_credit_v1` / `tmbank_debited_with_v1` |
| `canaraDebit` (`Dr.`) | review | review | review |

The one regression is the NPS merchant (the remark guesser is gone). It's a bank-rule job, and this
HDFCBK debit rule reads `NPS Contribution` from `npsDebit` (checked on the console, valid Java):
`INR\s*(?<amount>[\d,.]+) debited from HDFC Bank XX(?<account>\d+) on (?<date>\S+)\. Info: NEFT Dr-.*-(?<merchant>[^-]+?)(?:\s+\w)?\.\s*Avl bal`.

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

- `RealMessageTest`: parse with `SmsParser` backed by a test rule set holding the seven generic
  rules (a copy of the console's `data/generic-rules.json` under `src/test/resources`, test-only),
  plus the TMB rules where the test needs them; expectations as in the §4 table, `npsDebit`'s
  merchant updated.
- `RemoteRulesRepositoryTest`: `"*"` matches any sender; a bank rule at 10 beats a `"*"` rule at 4;
  no cached set → nothing matches, and after a sync the queued message is cleared by the re-check.
- `SmsRepositoryTest`: a message read twice (bank + app) is two transactions (the documented
  behaviour now); nothing references transfers.

## 9. Rollout

1. ~~Console: publish the seven generic rules~~ **Done: v16**, 2026-10-01, published from the
   console repo's `data/generic-rules.json` with the four `android_*` rules removed. Old app
   versions ignore `"*"` rules and keep using their templates.
2. App: §2–§6, release.
3. On the phone: Settings → Check now, then Import SMS history if you want old messages re-read
   under the new list.

## 10. Checklist

- [ ] §2 `applyRule` fills `accountLabel` from `account`; `ParsedSms` loses `referenceId`,
      `counterpartyAccount`.
- [ ] §3 `"*"` senders in `tryMatch` and `isIgnoredMessage`.
- [ ] §4 no bundled rules; Import SMS history syncs first; an empty cache matches nothing.
- [ ] §5 removals; review heuristics moved.
- [ ] §6 migration 7 → 8 + `MigrationTest`.
- [ ] §8 tests.
- [ ] REQUIREMENTS.md §5/§5.2; mark `BANK_RULES_FIRST.md` §2 superseded.
