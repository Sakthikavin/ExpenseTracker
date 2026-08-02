# ExpenseTracker — SMS Parsing Behaviour

What the app actually detects, what it stores, and where it currently falls short.

[ARCHITECTURE.md §6](ARCHITECTURE.md) describes the *shape* of the ingestion pipeline — which class
calls which. This doc is the companion for the *behaviour*: given a specific SMS, what happens, and
why. Every example below was produced by running the real regexes from the source against sample
messages, not by reading them by eye.

Related docs:
- [ARCHITECTURE.md](ARCHITECTURE.md) — the pipeline's structure, §6
- [expense-tracker-spec.md](expense-tracker-spec.md) — the three-tier design intent, §3

---

## 1. The three outcomes

Every message that reaches the app ends in exactly one of three states, decided by
[SmsParser.parse()](app/src/main/java/com/example/expensetracker/data/sms/SmsParser.kt#L8-L16).

| Outcome | `raw_sms` row | `transactions` row | User sees |
| --- | --- | --- | --- |
| `Parsed` | yes, `parseStatus = PARSED` | yes, created automatically | the transaction, immediately |
| `NeedsReview` | yes, `parseStatus = NEEDS_REVIEW` | no — not yet | an entry in the Review tab |
| `Ignored` | **none** | no | nothing; the message is discarded |

`Ignored` writes nothing at all. The `ParseStatus.IGNORED` enum value exists in
[RawSmsEntity.kt:7](app/src/main/java/com/example/expensetracker/data/local/entity/RawSmsEntity.kt#L7)
but is never assigned — if you ever want an audit trail of discarded messages, that is the hook.

---

## 2. Tier 1 — the built-in regex

[BankTemplates.kt](app/src/main/java/com/example/expensetracker/data/sms/BankTemplates.kt) holds six
templates (HDFC, SBI, ICICI, Axis, Kotak, Generic UPI), but all six are `GenericBankTemplate` and all
six delegate to the same `extractGeneric`. **The sender does not currently influence parsing at all** —
the `Generic UPI` entry is registered with the sender hint `""`, and `"anything".contains("")` is
always `true`, so every sender reaches the same two regexes.

`extractGeneric` tries debit first, then credit. Real banks put the amount on **either** side of the
verb, so each direction has two regexes, built from shared fragments by `directionRegexes`:

```
form A — amount first    "Rs 450.00 debited to swiggy@icici"     (HDFC, ICICI)
form B — verb first      "Debited Rs 1.00 ... to KEERTHANA KU"   (Federal, SBI)
```

Decomposed, using the debit direction:

```
(?i)                                  case-insensitive
(?:rs\.?|inr)\s*                      currency prefix: "Rs", "Rs.", "INR"
([\d,]+(?:\.\d{1,2})?)                GROUP 1 — amount: 450, 1,250.50
\s+(?:has\s+been\s+)?                 optional passive filler
(?:debited|debit|spent|paid|withdrawn)    the verb   (credit: credited|received)
.*?                                   lazy skip over account number, card name, etc.
\b(?:towards|to|at|from)\s+           the preposition   (credit: from|by)
(?!a/c\b|ac\b|account\b)              guard — never treat the source account as the payee
([A-Za-z0-9@._\-\s]{2,40}?)           GROUP 2 — merchant, lazy, 2–40 chars
(?:\s+on\b|\s+ref\b|\s+via\b|[.,]|$)  stop marker
```

Form B is the same with the verb and amount swapped, plus an optional connector so that both
`Debited Rs 1.00` and `debited by Rs.320.00` are covered.

Three details carry most of the weight:

- **the stop marker** keeps the lazy merchant group from swallowing the rest of the line;
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
   → DEBIT  ₹350.00   merchant "VPA merchant@ybl"

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

Many banks put each fact on its own line. The single-line regexes cannot reach these, because `.`
does not match a newline in Kotlin — and simply switching that on would be worse than useless: in the
Axis alert below, the first `to` reachable across lines belongs to `WhatsApp BAL to 917036165000`, so
the payee would become a phone number. `BlockFormat` reads the lines structurally instead, keeping
each fact bound to the line that states it.

```
✅ Sent Rs.58.00                        ✅ Debit INR 292.00
   From HDFC Bank A/C *3941                Axis Bank A/c XX4795
   To Google India Digital Serv            01-08-26 15:48:58
   On 24/02/26                             APY/500405010905/920010018
   Ref 119088866187                        WhatsApp BAL to 917036165000

   → DEBIT ₹58.00                          → DEBIT ₹292.00
     merchant "Google India Digital Serv"    merchant "APY"
     account  "*3941"                        account  "XX4795"
     ref      119088866187
```

The payee comes from the `To` line, never the `From` line. When there is no payee line at all — an
auto-debit mandate names only its scheme — the leading token of a reference line (`APY/…` for Atal
Pension Yojana, `NACH/…`, `ACH/…`) is used instead.

### Merchant vs. the account the money left

`debited from HDFC Bank XX3941` offers the *source account* where a payee should be. Candidates are
rejected when they contain masked digits (`XX3941`, `*3941`), carry no letters at all (phone numbers,
reference digits), or are a bare institution name (`HDFC Bank`).

When the payee is rejected and the message is a NEFT-style alert, the purpose is recovered from the
trailing narration:

```
Info: NEFT Dr-UTIB0CCH274-SAKTHI KAVIN S S-SANDOZ - MUM-HDFCH00842011992-NET BANKING SI -NPS Contribution M.
                                                                                          └──── used ────┘
→ merchant "NPS Contribution"
```

Banks and beneficiary names are written in block capitals; the purpose a human typed keeps its mixed
case, which is what makes it findable in that soup.

### What does not

Anything whose verb isn't in the list, or where the amount and verb aren't adjacent:

```
❌ Purchase of Rs 599.00 on your ICICI Card XX12 at NETFLIX.COM. Avl Lmt Rs 45000
      "purchase" is a noun here; it's in the review heuristic but not a parse verb

❌ EMI of Rs 4,500 will be debited to your loan account on 05-08-26
      future tense — "4,500 will be debited" separates amount from verb
```

Both fall through to the review queue rather than being lost.

> These examples are asserted in
> [BankTemplatesTest.kt](app/src/test/java/com/example/expensetracker/data/sms/BankTemplatesTest.kt).
> When a new bank format shows up, add it to that corpus first, then change the regex.

### Amounts are integer paise

[`parseAmountToMinorUnits`](app/src/main/java/com/example/expensetracker/data/sms/ParsedSms.kt#L18-L22)
strips commas, parses to `Double`, multiplies by 100 and rounds: `"1,250.50"` → `125050`. The `Double`
exists only for the length of that expression; nothing downstream stores money as a float.

---

## 3. Tier 2 — learned patterns

If tier 1 misses, the parser looks up every pattern learned for that sender and tries each in turn,
most-confirmed first ([SmsParser.kt](app/src/main/java/com/example/expensetracker/data/sms/SmsParser.kt)).
A sender legitimately needs more than one — a debit alert and a credit alert are worded differently.

The lookup key is **normalised**, not the raw sender string. Indian sender IDs carry a rotating
operator/circle prefix, so `AD-FEDBNK`, `VM-FEDBNK` and `JD-FEDBNK-S` are all the same bank;
`PatternLearner.normaliseSender` reduces them to `FEDBNK`. Without this a pattern would stop working
for a reason that has nothing to do with the message wording.

`applyToBody` compiles the stored regex inside `runCatching`, so a malformed stored pattern degrades
to "no match" rather than crashing the receiver.

How a pattern gets created is covered in [§5](#5-the-learning-loop).

---

## 4. Tier 3 — the "is this even financial?" heuristic

```kotlin
fun looksFinancial(body: String): Boolean =
    AMOUNT_HINT.containsMatchIn(body) && TRANSACTIONAL_KEYWORD_HINT.containsMatchIn(body)
```

Both conditions must hold:

- **an amount** — `(?:rs\.?|inr)\s*[\d,]+`
- **a keyword** — `debited|credited|debit|credit|spent|paid|received|withdrawn|purchase`

True → `NeedsReview`. False → `Ignored`.

```
⚠️ EMI of Rs 4,500 will be debited to your loan account on 05-08-26
      queued — note it is a *future* debit, but nothing distinguishes tense

❌ Your OTP is 445566. Do not share.                       no amount
❌ Dear customer, your a/c balance is Rs 15,000.00         amount, but "balance" isn't a keyword
❌ Get 50% off! Spend Rs 999 and get Rs 200 cashback.      "Spend" ≠ "spent"
```

That last one is filtered by luck rather than design. A promotional message using any listed keyword
in past tense would land in the review queue.

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

## 5b. One payment, two messages

Banks routinely send two SMS for a single transfer. An NPS contribution produces both of these:

```
UPDATE: INR 5,000.00 debited from HDFC Bank XX3941 on 05-MAR-26. Info: NEFT
Dr-UTIB0CCH274-SAKTHI KAVIN S S-...-HDFCH00842011992-...-NPS Contribution M. Avl bal:INR 84,966.79

HDFC Bank : NEFT money transfer Txn No HDFCH00842011992 for Rs INR 5,000.00
has been credited to SAKTHI KAVIN S S on 05-03-2026 at 04:01:54
```

₹5,000 moved once. Recorded naively that is a ₹5,000 expense *and* ₹5,000 of income.

**The exact link is the shared reference** `HDFCH00842011992` — unlabelled in the first message,
labelled `Txn No` in the second. `SmsReferenceParser` pulls both labelled references (`Ref`, `UTR`,
`Txn No`) and unlabelled bank tokens (a short alphabetic prefix followed by 10+ digits, which is what
keeps the IFSC `UTIB0CCH274` out of the results).

`SmsRepository.reconcileWithExisting` then:

1. looks for a stored SMS transaction with the same `referenceId`;
2. failing that, looks for the same amount, on the same day, in the opposite direction;
3. if either hits, links the new raw SMS to the **existing** transaction and creates nothing new.

The surviving row is always the **debit** — the money left the account, so that is the truthful
record — regardless of which message arrived first. Both raw messages stay in `raw_sms`, both
pointing at the one transaction, so nothing is lost for audit.

Rule 2 is deliberately narrow. Same amount, same day, opposite direction is *also* exactly what a
refund looks like, so it only applies when the message reads like a bank transfer (`NEFT`, `IMPS`,
`RTGS`, `UPI`, `money transfer`). Without that guard a same-day refund would be swallowed into the
purchase it reversed — which
[`aSameDayRefundIsNotMergedIntoThePurchase`](app/src/androidTest/java/com/example/expensetracker/data/repository/SmsRepositoryTest.kt)
exists to prevent.

> Two identical messages carrying the same reference are the same payment however far apart they
> arrive. Two *different* payments never share a bank reference.

---

## 5c. Transfers between your own accounts

Moving ₹10,000 from HDFC to ICICI produces two messages, and **both legs are real** — each account's
balance genuinely changed. Together, though, they are neither spending nor income: the money never
left your control.

This is the opposite treatment from [§5b](#5b-one-payment-two-messages), and the distinction matters:

| | NPS pair | Self-transfer |
| --- | --- | --- |
| What happened | one event, reported twice by one bank | one event, two real legs in two accounts |
| Accounts named | one (or none on the second message) | two, and they differ |
| Handling | **merge** — keep one row | **pair** — keep both, link them |
| Why | the second row is noise | dropping a leg would corrupt an account's history |

`SmsRepository` tells them apart on exactly that signal: if both messages name an account and the
names differ, it refuses to merge and leaves them for `TransferMatcher` to pair. One bank describing
a single event twice never names two different accounts.

### How a pair is recognised

`TransferMatcher` considers only opposite-direction transactions within 72 hours whose amounts differ
by at most ₹25 — the tolerance covers IMPS fees, where ₹10,005 leaves and ₹10,000 arrives. Among
those it links **automatically** when any of:

1. the two share a bank reference (a UTR appearing in both messages — proof, not inference);
2. both account labels are ones you've claimed on **My accounts**;
3. the debit named a destination account you've claimed.

Anything weaker is only *suggested*, never linked silently.

### My accounts

The parser already extracts `XX3941`, `*3941`, `XX4795` from your messages, so the screen is a list
of toggles rather than a form asking for account numbers — mark which are yours, optionally name
them ("HDFC Savings"). That registry is what makes rule 2 work for transfers with no shared UTR.

### What you see

Both legs stay in the database, linked by `transferGroupId`, and collapse into one row:

```
2 Aug 2026    ⇄ Transfer · XX3941 → XX4795          ₹10,000.00
              Not counted as spending
```

Neither leg counts toward spend, income, or budgets. The dashboard states the amount separately —
*"Plus ₹10,000.00 moved between your own accounts, not counted as income or expense"* — because
money that silently disappears from a total is worse than money counted wrongly.

**Marking one by hand:** the ⋮ menu on any transaction offers *Mark as transfer*, listing nearby
opposite-direction transactions closest-amount-first. *No matching message* covers the case where
only one bank sent an alert. *Not a transfer* on a paired row unlinks both legs, and they start
counting again immediately.

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

### Path C — ignored

Body `Dear customer, your a/c balance is Rs 15,000.00 as on 02-08-26` — amount present, no
transactional keyword → `Ignored` → nothing written.

---

## 7. Status and remaining limits

The eight gaps this document originally listed have all been closed, each with a test that fails if
it regresses:

| Was | Now | Proof |
| --- | --- | --- |
| Learned patterns froze dates and balances | `generaliseLiteral` turns volatile spans into wildcards | `PatternLearnerTest` |
| Verb-first phrasing and `spent`/`paid`/`withdrawn` didn't parse | both orderings, five debit verbs | `BankTemplatesTest` |
| Learned patterns always replayed as `DEBIT` | direction stored per pattern (schema v2) | `PatternLearnerTest` |
| Auto-parsed rows had no `linkedTransactionId` | written back after the transaction is created | `SmsRepositoryTest` |
| No deduplication | unique index on `(sender, body, receivedAt)` | `SmsRepositoryTest`, `MigrationTest` |
| `occurredAt` was `Clock.System.now()` | the message's own date, else its receipt time | `SmsRepositoryTest` |
| Sender allowlist was dead code | real routing, 16 senders, explicit fallback | `BankTemplatesTest` |
| `parseAmountToMinorUnits` could throw | returns null; `BigDecimal` instead of `Double` | `BankTemplatesTest` |
| Multi-line alerts couldn't parse at all | `BlockFormat` reads them line by line | `RealMessageTest` |
| `Sent …` was silently discarded — not even queued | `sent`/`transferred` are parse verbs *and* review keywords | `RealMessageTest` |
| The source account was taken as the merchant | account-shaped candidates rejected; NEFT purpose recovered from the narration | `RealMessageTest` |
| One transfer was recorded as two transactions | shared-reference matching, then a narrow amount/date fallback | `SmsRepositoryTest` |
| Self-transfers counted as spending *and* income | both legs paired via `transferGroupId`, excluded from all totals | `TransferMatcherTest`, `SmsRepositoryTest` |
| `IMPS/<utr>/<bank>` references weren't extracted | rail-prefixed references recognised | `RealMessageTest` |
| "to &lt;my own account&gt;" failed to parse at all | resolves to the destination institution, with the account captured | `RealMessageTest` |

### What still limits accuracy

These are design limits rather than defects — worth knowing before trusting the numbers:

- **Tense is not modelled.** `EMI of Rs 4,500 will be debited on 05-08-26` queues for review like any
  other message; nothing marks it as an announcement of a future debit.
- **The review heuristic is keyword-based**, so a promotional message written in the past tense
  ("you paid too much for…") will reach the queue. It cannot create a transaction on its own, so the
  cost is noise, not wrong data.
- **Deduplication has two layers.** A redelivered broadcast is caught by the unique
  `(sender, body, receivedAt)` index; two *different* messages about one payment are caught by the
  shared bank reference. The amount/date fallback only fires on transfer wording, so a refund
  survives as its own transaction.
- **A message with no reference and no transfer wording can still double-count** if a bank ever
  sends two differently-worded alerts for one payment without a shared reference. Nothing in the
  current corpus does this.
- **Merchant quality varies by format.** A UPI alert names the payee outright; a NEFT alert only
  carries a free-text narration, so `NPS Contribution` is a best-effort read of a field banks do not
  structure.
- **A learned pattern is only as good as the message it came from.** Generalisation covers dates,
  times, reference numbers and balances; a bank that varies its wording in some other way will need
  a second confirmation, which the learner now accepts (it stores an additional pattern rather than
  refusing).
- **`Direction` for tier-1 matches comes from the verb**, so an unusual construction could in
  principle mislabel one. Nothing in the current corpus does.

### Schema note

- **v2** added `learned_patterns.direction` and the unique dedup index on `raw_sms`.
  `MIGRATION_1_2` deletes pre-existing duplicate rows *before* creating that index, because
  `CREATE UNIQUE INDEX` fails outright on a table that already violates it.
- **v3** added `transactions.referenceId` and its (non-unique) index. `MIGRATION_2_3` is a plain
  column addition; existing rows get `NULL`, which is correct — they predate reference capture.
- **v4** added `transactions.transferGroupId` and the `own_accounts` table. `MIGRATION_3_4` adds
  both; existing rows are not transfers, which is the right default.

There is no destructive fallback anywhere in `AppDatabase`: the database is the user's only copy of
their transactions. Both migrations, and the 1 → 3 path a phone on the original release actually
takes, are covered by
[MigrationTest](app/src/androidTest/java/com/example/expensetracker/data/local/MigrationTest.kt).
