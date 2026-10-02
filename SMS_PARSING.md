# ExpenseTracker — SMS Parsing Behaviour

What the app actually detects, what it stores, and where it currently falls short.

[ARCHITECTURE.md §6](ARCHITECTURE.md) describes the *shape* of the ingestion pipeline — which class
calls which. This doc is the companion for the *behaviour*: given a specific SMS, what happens, and
why. Every example below was produced by running the real regexes from the source against sample
messages, not by reading them by eye.

Related docs:
- [ARCHITECTURE.md](ARCHITECTURE.md) — the pipeline's structure, §6

---

## 1. The outcomes

Every message that reaches the app ends in exactly one of these, decided by
[SmsParser.parse()](app/src/main/java/com/example/expensetracker/data/sms/SmsParser.kt).

| Outcome | `raw_sms` row | `transactions` row | User sees |
| --- | --- | --- | --- |
| `Parsed` | yes, `parseStatus = PARSED` | yes, created automatically | the transaction, immediately |
| `NeedsReview` | yes, `parseStatus = NEEDS_REVIEW` | no — not yet | an entry in the Review tab |
| `IgnoredAsNoise` | yes, `parseStatus = IGNORED` | no | nothing, but it stays auditable |
| `Discarded` | yes, `parseStatus = DISCARDED`, capped at the newest 200 | no | Settings → "Messages I skipped" |
| `Ignored` | **none** | no | nothing; the message is dropped outright |

The line between the last two is whether the message mentions money at all. Something that mentions
an amount and was still turned away is kept, because that's how a mistake in the heuristic becomes
findable — a Canara alert writing `Dr.` instead of "debited" once went missing with no trace
anywhere. Something that mentions no money (an OTP, a delivery notification, a personal text) is
dropped, which is what stops the skipped list becoming a second copy of the inbox.

---

## 2. The published rule list

**The app ships no rules and no built-in templates.** Everything that turns an SMS into a
transaction is published from the rules console and arrives by sync, cached on the device
(`data/remoterules/PARSING_ARCHITECTURE.md`). A bank changing its wording is a rule edit, not an app
release — and on a phone that has never synced, nothing parses at all and every financial-looking
message waits in the review queue until the first sync re-reads it.

A rule is matched against the raw message body and yields exactly five things: amount, direction,
merchant, the account label if it captures one, and the date (read from the whole message by
`SmsDateParser`). Rules are tried for this sender by `priority`, highest first.

Two kinds, and the distinction is only priority:

- **Bank rules** (priority 10 and up) — written for one sender against its real messages. These are
  where accuracy comes from, and the only rules that fill the account label.
- **Generic rules** (priority 6 down to 1, `senders: ["*"]`) — the seven that used to be the app's
  built-in templates, including the two that read multi-line "block" alerts. They apply to every
  sender and catch the common Indian bank/UPI shapes.

The generic debit rule, decomposed — this is the published pattern, not a paraphrase:

```
(?i)                                  case-insensitive
(?:rs\.?|inr)\s*                      currency prefix: "Rs", "Rs.", "INR"
(?<amount>\d[\d,]*(?:\.\d{1,2})?)     amount: 450, 1,250.50
\s+(?:has\s+been\s+)?                 optional passive filler
(?:debited|debit|spent|paid|withdrawn|sent|transferred)
.*?                                   lazy skip over account number, card name, etc.
\b(?:towards|to|at|from)\s+           the preposition   (credit: from|by|to)
(?!(?:your\s+)?(?:a/c|ac|account|card)\b)   guard — never the source account
(?:vpa\s+)?                           skip the label, keep the handle
(?<merchant>[A-Za-z0-9@._/\-\s]{2,40}?)
(?:\s+on\b|\s+ref\b|\s+via\b|\s+no\b|[.,](?![\w.\-]*@)|$)   stop marker
```

The verb-first rule is the same with the verb and amount swapped, plus an optional connector so both
`Debited Rs 1.00` and `debited by Rs.320.00` are covered.

Four details carry most of the weight:

- **the stop marker** keeps the lazy merchant group from swallowing the rest of the line — and its
  `[.,](?![\w.\-]*@)` means a dot *inside a handle* no longer ends the name, so
  `landlord.ravi@ybl` stays whole instead of becoming `VPA landlord`;
- **`\s+no\b` as a stop** ends the merchant before a trailing reference, so
  `VPA kumarstores@okaxis No 427381920113` reads as `kumarstores@okaxis`;
- **the `a/c` guard** matters whenever `from` is a valid preposition — in
  `Debited Rs 1.00 from a/c X6686 ... to KEERTHANA KU`, the account offers itself as the merchant
  first, and without the lookahead it would win and the real payee would be lost;
- **`towards` precedes `to`** in the alternation, so the shorter branch can't win on a prefix.

### What matches

```
✅ Rs 450.00 debited to swiggy@icici on 02-08-26. Ref 4432112. -HDFC Bank
   → DEBIT  ₹450.00   merchant "swiggy@icici"

✅ INR 1,250.50 has been debited to AMAZON PAY on 01-08-26.
   → DEBIT  ₹1250.50  merchant "AMAZON PAY"

✅ Rs.99 debited at STARBUCKS INDIA on 02-08-26
   → DEBIT  ₹99.00    merchant "STARBUCKS INDIA"

✅ Rs 250 debited towards ELECTRICITY BILL.
   → DEBIT  ₹250.00   merchant "ELECTRICITY BILL"

✅ INR 350 debited from a/c XX99 to VPA merchant@ybl on 02Aug26
   → DEBIT  ₹350.00   merchant "merchant@ybl"      (the "VPA" label is skipped)

✅ Rs 5000 credited from RAHUL SHARMA on 01-08-26. Avl bal Rs 21000
   → CREDIT ₹5000.00  merchant "RAHUL SHARMA"

✅ Debited Rs 1.00 from a/c X6686 on 01Aug26 19:00 via UPI to KEERTHANA KU.
   Ref 621312687340.Bal Rs 42727.9. -Federal Bank
   → DEBIT  ₹1.00     merchant "KEERTHANA KU"        (verb-first + a/c guard)

✅ Your a/c XX1234 is debited by Rs.320.00 on 02-08-26 at ZOMATO. Avl Bal 5000
   → DEBIT  ₹320.00   merchant "ZOMATO"              (connector "by")

✅ You have spent Rs 780 on your HDFC Credit Card at BIGBASKET
   → DEBIT  ₹780.00   merchant "BIGBASKET"

✅ Rs 1200 paid to Uber India via UPI
   → DEBIT  ₹1200.00  merchant "Uber India"          (stopped by " via")

✅ Rs.2000 withdrawn from ATM SBI Anna Nagar on 02-08-26
   → DEBIT  ₹2000.00  merchant "ATM SBI Anna Nagar"
```

### Multi-line "block" alerts

Many banks put each fact on its own line, and a single-line pattern can't reach them. Letting `.`
cross newlines would be worse than useless: in the Axis alert below, the first `to` reachable across
lines belongs to `WhatsApp BAL to 917036165000`, so the payee would become a phone number. The three
block rules use `(?im)` with `^…$` per line instead, which binds each fact to the line that states
it — `To` has to *start* a line to be the payee.

```
✅ Sent Rs.58.00                        ✅ Debit INR 292.00
   From HDFC Bank A/C *3941                Axis Bank A/c XX4795
   To Google India Digital Serv            01-08-26 15:48:58
   On 24/02/26                             APY/500405010905/920010018
   Ref 119088866187                        WhatsApp BAL to 917036165000

   → DEBIT ₹58.00                          → DEBIT ₹292.00
     merchant "Google India Digital Serv"    merchant "APY"
```

Neither fills an account label: no generic rule captures one, so that field stays blank unless a
bank rule for the sender maps it.

The payee comes from the `To` line, never the `From` line. When there is no payee line at all — an
auto-debit mandate names only its scheme — the leading token of a reference line (`APY/…` for Atal
Pension Yojana, `NACH/…`, `ACH/…`) is used instead.

### Merchant vs. the account the money left

`debited from HDFC Bank XX3941` offers the *source account* where a payee should be. The generic
rules refuse `a/c`, `ac`, `account`, `card` and `your a/c` outright — but the guard is a prefix
check, not a judgement, so `from HDFC Bank XX3941` still reads as a merchant named
"HDFC Bank XX3941".

That's the one place the move to published rules cost accuracy. The app used to recover the real
purpose from a NEFT narration:

```
Info: NEFT Dr-UTIB0CCH274-SAKTHI KAVIN S S-SANDOZ - MUM-HDFCH00842011992-NET BANKING SI -NPS Contribution M.
                                                                                          └──── used ────┘
→ merchant "NPS Contribution"
```

That guesser is gone, and reading a narration like this is a bank rule's job now — a HDFCBK rule can
capture the same span, and until one is published the merchant reads "HDFC Bank XX3941".

### What does not

Anything whose verb isn't in the list, or where the amount and verb aren't adjacent:

```
❌ Purchase of Rs 599.00 on your ICICI Card XX12 at NETFLIX.COM. Avl Lmt Rs 45000
      "purchase" is a noun here; it's in the review heuristic but not a parse verb

❌ EMI of Rs 4,500 will be debited to your loan account on 05-08-26
      future tense — "4,500 will be debited" separates amount from verb
```

Both fall through to the review queue rather than being lost.

> The corpus these are checked against is
> [RealMessageTest.kt](app/src/test/java/com/example/expensetracker/data/sms/RealMessageTest.kt),
> which runs the **real published rules** — `src/test/resources/generic-rules.json` is a copy of what
> the console publishes, so an expectation here is evidence rather than a regex written to pass.
> When a new bank format shows up, add the message to `RealMessages` first and watch it fail; the fix
> is then a rule on the console, and a refreshed copy of the file.

### Amounts are integer paise

[`parseAmountToMinorUnits`](app/src/main/java/com/example/expensetracker/data/sms/ParsedSms.kt)
strips commas and converts through `BigDecimal`: `"1,250.50"` → `125050`. Never `Double` — the
conversion is exact at any magnitude instead of relying on a rounding step — and it returns null on
anything unreadable, so a rule whose amount group caught something odd falls through instead of
storing a wrong number.

---

## 3. Learned patterns — the per-device fallback

If no rule reads the message, the parser looks up every pattern learned for that sender and tries each in turn,
most-confirmed first ([SmsParser.kt](app/src/main/java/com/example/expensetracker/data/sms/SmsParser.kt)).
A sender legitimately needs more than one — a debit alert and a credit alert are worded differently.
Rules come first: a rule was reviewed against real samples and reaches every phone, while a learned
pattern is a guess this device made from a single confirmation.

The lookup key is **normalised**, not the raw sender string. Indian sender IDs carry a rotating
operator/circle prefix, so `AD-FEDBNK`, `VM-FEDBNK` and `JD-FEDBNK-S` are all the same bank;
`PatternLearner.normaliseSender` reduces them to `FEDBNK`. Without this a pattern would stop working
for a reason that has nothing to do with the message wording.

`applyToBody` compiles the stored regex inside `runCatching`, so a malformed stored pattern degrades
to "no match" rather than crashing the receiver.

How a pattern gets created is covered in [§5](#5-the-learning-loop).

---

## 4. The review-queue heuristic

The last question, and the only message-reading the app still does itself
([ReviewHeuristics.kt](app/src/main/java/com/example/expensetracker/data/sms/ReviewHeuristics.kt)).
It extracts nothing — it only decides whether an unmatched message is worth a person's attention.

An amount is required either way, and then there are two ways to qualify:

- **a transactional verb** — `debited|credited|debit|credit|spent|paid|received|withdrawn|purchase|sent|transferred|transfer`
- **or the *shape* of a bank alert** — a masked account, an account label, a balance, a UPI handle or
  a labelled reference — **plus an amount that isn't the balance**

The second route is what a closed list of verbs couldn't do. Canara writes `Dr.`/`Cr.`, an ATM writes
`W/D`; before this, such a message was dropped with no row at all, so exactly the messages most in
need of a rule could never ask for one. Requiring an amount that survives having the balance
stripped out is what still keeps a bare balance enquiry out: a balance enquiry's only amount *is* its
balance.

```
⚠️ Acct XXX167 Dr. INR 26.00 on 29/09/26 to Euronet Serv; Bal INR 49,511.88
      queued — no verb, but a masked account, a balance, and ₹26.00 that isn't the balance

⚠️ Purchase of Rs 599.00 on your ICICI Card XX12 at NETFLIX.COM. Avl Lmt Rs 45000
      queued — "purchase" is a review keyword but no rule reads this shape

🗑 Reminder: your order of Rs 1,299 is out for delivery.
      skipped — mentions money, carries no bank-alert structure; kept where you can see it

🗑 Dear customer, your a/c balance is Rs 15,000.00
      skipped — its only amount is the balance

❌ Your OTP is 445566. Do not share.                        no amount at all; no row
❌ Get 50% off! Spend Rs 999 and get Rs 200 cashback.       "Spend" ≠ "spent", no alert structure
```

Over-admission is corrected from the console with an ignore rule, not an app release
(`data/remoterules/IGNORE_RULES.md`). Under-admission lands in the skipped list, where it can still
be found and moved to review by hand.

A floor also applies on this path only: an amount under ₹2 (configurable in Settings) is treated as a
verification ping rather than spending. A rule's match is never floored — a ₹1 UPI payment a rule
read is a real payment.

---

## 5. The learning loop

When you confirm a queued message,
[`confirmReview`](app/src/main/java/com/example/expensetracker/data/repository/SmsRepository.kt)
runs four steps:

1. create the transaction from your confirmed values;
2. flip the raw row to `PARSED` and set `linkedTransactionId`;
3. if an existing pattern **already matches this body** → increment its `confirmedCount` and stop;
4. otherwise → `PatternLearner.derive(...)` and store an additional pattern.

Step 3 tests the stored patterns against the actual message rather than just checking whether the
sender has *any* pattern. That distinction matters: the previous behaviour meant the first pattern
derived for a sender was permanent, so a pattern that never matched anything could never be replaced.

### How `derive` builds a regex

It does not understand the message. It locates where your confirmed values physically sit in the
original text and punches capture groups into those exact character spans
([PatternLearner.kt](app/src/main/java/com/example/expensetracker/data/sms/PatternLearner.kt)):

1. `findAmountSpan` tries `"599.00"`, then the comma-grouped form, then bare `"599"`.
2. `findMerchantSpan` does a case-insensitive `indexOf` of the merchant text.
3. Spans are sorted by position; the builder walks left to right, emitting a capture group per span
   and passing the text between them through `generaliseLiteral`.

**`generaliseLiteral` is what makes the pattern reusable.** It escapes the fixed wording but replaces
the parts that change between two otherwise identical alerts — dates, clock times, reference numbers
and running balances — with equivalent wildcards. Freezing them would guarantee that no future
message ever matches.

Worked through with sender `AD-ICICIB` and the body
`Purchase of Rs 599.00 on your ICICI Card XX12 at NETFLIX.COM. Avl Lmt Rs 45000`, confirmed as
₹599.00 at NETFLIX.COM, the stored pattern is:

```
Purchase\ of\ Rs\ ([\d,]+(?:\.\d{1,2})?)\ on\ your\ ICICI\ Card\ XX[\d,.:/\-]+\ at\ (.+?)\.\ Avl\ Lmt\ Rs\ [\d,.]+
                  └──── group 1: amount ────┘                    └ generalised ┘  └ grp 2 ┘        └ generalised ┘

fieldMap = { amount: 1, merchant: 2 },  direction = DEBIT
```

Replayed against the next message — different amount, merchant, card digits and credit limit:

```
✅ Purchase of Rs 249.00 on your ICICI Card XX12 at SPOTIFY.COM. Avl Lmt Rs 44751
   → DEBIT ₹249.00, merchant "SPOTIFY.COM"
```

The direction is stored on the pattern rather than re-derived, because messages rarely state it in a
position a capture group can reach. A confirmed salary credit therefore replays as a credit.

> The full round trip — confirm a queued message, then watch a later one auto-parse — is asserted in
> [SmsRepositoryTest](app/src/androidTest/java/com/example/expensetracker/data/repository/SmsRepositoryTest.kt),
> including across a change of sender prefix.

---

## 5b. One payment, two messages — and self-transfers

Banks routinely send two SMS for a single transfer. An NPS contribution produces both of these:

```
UPDATE: INR 5,000.00 debited from HDFC Bank XX3941 on 05-MAR-26. Info: NEFT
Dr-UTIB0CCH274-SAKTHI KAVIN S S-...-HDFCH00842011992-...-NPS Contribution M. Avl bal:INR 84,966.79

HDFC Bank : NEFT money transfer Txn No HDFCH00842011992 for Rs INR 5,000.00
has been credited to SAKTHI KAVIN S S on 05-03-2026 at 04:01:54
```

₹5,000 moved once, and the app now records **two** transactions — a ₹5,000 debit and a ₹5,000
credit. That is deliberate (`data/remoterules/PARSING_ARCHITECTURE.md` §7). It used to match the
shared reference `HDFCH00842011992` and merge them, and separately to pair the two legs of a
transfer between your own accounts under a `transferGroupId` so that neither counted as spending or
income. Both mechanisms are gone, along with the "My accounts" screen that fed the second one.

What replaces them is console-side and explicit:

- **two alerts for one payment** (the bank's and a UPI app's): discard the app's sender, or publish
  an ignore rule for its wording;
- **a transfer to your own account**: an ignore rule on its wording. Published rules reach every
  phone and the rules document is publicly readable, so an account number must never appear in a
  pattern.

Why it went: three layers each read the same message, and the two that ran *after* a match — the
reference matching and the transfer pairing — were invisible to the console. A rule author could see
that a rule read a message but not that the app then merged it into another row. Guessing less, and
saying so, beat guessing well in a place nobody could inspect.

One duplicate check survives, because it guesses nothing: `findExactBodyResend` matches a
**byte-identical** body from the same sender and links the new row to the existing transaction. The
unique index on `raw_sms(sender, body, receivedAt)` already rejects a redelivery carrying the same
timestamp; this catches the one that arrives later.

**Upgrading:** `MIGRATION_7_8` drops `own_accounts` and clears every `transferGroupId`, so months
that contained a paired transfer now report both more spending and more income. Nothing is deleted —
both legs were always stored — so a leg can be removed by hand if it matters.

---

## 6. Reference: end-to-end traces

### Path A — auto-parsed

Sender `VM-HDFCBK`, body `Rs 450.00 debited to swiggy@icici on 02-08-26. Ref 4432112.`

| Step | What happens |
| --- | --- |
| 1 | `SmsReceiver` reassembles the multipart body and takes the network's timestamp, then calls `ingest` on `Dispatchers.IO` |
| 2 | `findMatch` → HDFC template; if it failed to extract, the generic fallback still gets a turn |
| 3 | the amount-first debit regex matches; group 1 `450.00`, group 2 `swiggy@icici` (stopped by ` on`) |
| 4 | `parseAmountToMinorUnits` → `45000`; `SmsDateParser` → 02 Aug 2026; `ACCOUNT_LABEL` → `""` |
| 5 | `raw_sms` inserted with `PARSED`. A duplicate is rejected here and ingestion stops |
| 6 | `transactions` row created with `source = SMS` and the message's own date |
| 7 | `raw_sms.linkedTransactionId` written back |

Result: one raw row, one uncategorised transaction, no user action.

### Path B — queued, then confirmed

Sender `AD-ICICIB`, body `Purchase of Rs 599.00 on your ICICI Card XX12 at NETFLIX.COM. Avl Lmt Rs 45000`

| Step | What happens |
| --- | --- |
| 1–2 | ICICI template matches the sender but can't extract — "purchase" is a noun here, not a parse verb — so the generic fallback runs and also declines |
| 3 | no learned pattern for `ICICIB` |
| 4 | `looksFinancial` → `Rs 599` ✓ + `Purchase` ✓ → `NeedsReview` |
| 5 | `raw_sms` inserted with `NEEDS_REVIEW`; **no transaction** |
| 6 | `observeByStatus` is a `Flow`, so the Review tab updates instantly |
| 7 | user confirms → transaction created, row → `PARSED`, `derive` runs (see §5) |

### Path C — skipped, and dropped

Body `Dear customer, your a/c balance is Rs 15,000.00 as on 02-08-26` — an amount, but its only
amount is the balance → `Discarded` → a `raw_sms` row under `DISCARDED`, listed in Settings →
"Messages I skipped", no transaction.

Body `Your OTP is 445566. Do not share.` — no amount at all → `Ignored` → nothing written anywhere.

---

## 7. Status and remaining limits

The gaps this document originally listed have all been closed, each with a test that fails if it
regresses. Where a row's proof used to be `BankTemplatesTest`, the behaviour now lives in a published
rule and the proof is `RealMessageTest` running the real rule set.

| Was | Now | Proof |
| --- | --- | --- |
| Learned patterns froze dates and balances | `generaliseLiteral` turns volatile spans into wildcards | `PatternLearnerTest` |
| Verb-first phrasing and `spent`/`paid`/`withdrawn` didn't parse | both orderings, seven debit verbs | `RealMessageTest` |
| Learned patterns always replayed as `DEBIT` | direction stored per pattern (schema v2) | `PatternLearnerTest` |
| Auto-parsed rows had no `linkedTransactionId` | written back after the transaction is created | `SmsRepositoryTest` |
| No deduplication | unique index on `(sender, body, receivedAt)`, plus the body-match resend guard | `SmsRepositoryTest`, `MigrationTest` |
| `occurredAt` was `Clock.System.now()` | the message's own date, else its receipt time | `SmsRepositoryTest` |
| `parseAmountToMinorUnits` could throw | returns null; `BigDecimal` instead of `Double` | `RealMessageTest` |
| Multi-line alerts couldn't parse at all | three published block rules read them line by line | `RealMessageTest` |
| `Sent …` was silently discarded — not even queued | `sent`/`transferred` are rule verbs *and* review keywords | `RealMessageTest` |
| A message no verb matched left no row at all | kept as `DISCARDED` and listed under "Messages I skipped" | `SmsRepositoryTest`, `SmsParserRoutingTest` |
| A merchant stopped at the dot inside a UPI handle | the stop marker ignores a dot followed by a handle | `RealMessageTest` |
| `IMPS/<utr>/<bank>` references weren't extracted | rail-prefixed references recognised (as an alert marker) | `RealMessageTest` |
| A bank's wording change needed an app release | it needs a rule published from the console | `RemoteRulesRepositoryTest` |

### What still limits accuracy

These are design limits rather than defects — worth knowing before trusting the numbers:

- **Nothing parses before the first sync.** A fresh install holds no rules, so every financial-looking
  message waits in the review queue until a sync arrives and `reparseNeedsReview` re-reads it. Import
  SMS history syncs first for this reason, and says so if it couldn't.
- **One payment reported twice is two transactions.** Two *different* messages about the same money —
  the bank's alert and a UPI app's, or a debit and its NEFT confirmation — both become rows. Fixing
  it is a console action (a discarded sender or an ignore rule), not something the device guesses.
  A redelivery of the *same* message is still collapsed.
- **Self-transfers count as both spending and income.** Moving ₹10,000 between your own accounts
  produces a ₹10,000 debit and a ₹10,000 credit, and both land in the totals.
- **The account label is often blank.** Only a bank rule that maps an `account` group fills it; none
  of the generic rules do.
- **Merchant quality varies by format and by whether a bank rule exists.** A UPI alert names the payee
  outright. A NEFT alert carries only a free-text narration, and with no bank rule for that sender the
  generic rule reads the source account instead ("HDFC Bank XX3941"). Eight of the published bank
  rules capture no merchant at all, which shows as "(no merchant)".
- **Tense is not modelled beyond a filter.** A future-tense notice with no past-tense confirmation is
  turned away as noise; anything subtler queues for review like any other message.
- **The review heuristic admits by structure as well as keywords**, so a promotion that quotes an
  amount and happens to carry a reference-shaped token can reach the queue. It cannot create a
  transaction on its own, so the cost is noise, not wrong data — and an ignore rule removes it.
- **A learned pattern is only as good as the message it came from.** Generalisation covers dates,
  times, reference numbers and balances; a bank that varies its wording in some other way needs a
  second confirmation, which the learner accepts (it stores an additional pattern rather than
  refusing).

### Schema note

- **v2** added `learned_patterns.direction` and the unique dedup index on `raw_sms`.
  `MIGRATION_1_2` deletes pre-existing duplicate rows *before* creating that index, because
  `CREATE UNIQUE INDEX` fails outright on a table that already violates it.
- **v3** added `transactions.referenceId`, **v4** `transactions.transferGroupId` and the
  `own_accounts` table, **v5** the merchant-category rules table, **v6** `raw_sms.submittedAt`.
- **v7** added eleven categories by migration, because the seed callback only runs when the database
  is created and would never reach an existing install.
- **v8** dropped `own_accounts` and cleared every `transferGroupId`. The two columns stay: dropping a
  column in SQLite means rebuilding the table, which isn't worth it for two nullable columns nothing
  reads.

There is no destructive fallback anywhere in `AppDatabase`: the database is the user's only copy of
their transactions. Every migration, and the multi-step paths a phone on an older release actually
takes, are covered by
[MigrationTest](app/src/androidTest/java/com/example/expensetracker/data/local/MigrationTest.kt).
