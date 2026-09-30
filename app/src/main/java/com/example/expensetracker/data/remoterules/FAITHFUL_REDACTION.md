# Faithful redaction, and re-checking the whole review queue

Android-side spec. Companion to `REQUIREMENTS.md` (§6.1 redaction, §8.1 backlog re-parse) and
`IGNORE_RULES.md` in this folder. Fold the changes into REQUIREMENTS.md when you implement.

## 1. What went wrong

The console's TMB rules (published v7) passed every sample and matched no real TMB message.
The real SMS:

```
Your A/c No.XXXX0017 is credited with Rs.5,000.00 on 30-09-2026 11:03 AM and HDFC A/c linked
to sakthikavincit-2@okaxis is debited (UPI Ref No.663989123496).Current AVBL bal is Rs.10214.44 - TMB
```

The template the phone uploaded:

```
… (UPI Ref No.<REF>).Current AVBL bal is <BAL> - TMB
```

The balance rule dropped `Rs.` along with the number, so everyone writing the rule saw a
shape that doesn't exist, and wrote `bal is (?<balance>[\d,.]+)`. The console now tolerates it
(`bal is (?:Rs\.?|INR)?\s*…`), but other banks will hit the same thing.

The second problem: after pulling a new rules version, `reparseNeedsReview` only re-checks the
200 newest review messages (`REPARSE_LIMIT`). A queue of 728 leaves 528 messages that a new rule
never gets to clear, e.g. a 13 Sep TMB debit.

## 2. The rule: redaction replaces values, and changes nothing else

A template must differ from the SMS **only** where a value became a placeholder. Currency
words, separators, spacing and digit counts are message *shape*, not personal data, and rules
depend on them.

Where `Redactor` breaks that today:

| Rule | SMS | Template now | Should be |
|---|---|---|---|
| Balance | `bal is Rs.10214.44` | `bal is <BAL>` | `bal is Rs.<BAL>` |
| Balance | `Avl Bal:INR 1,234` | `Avl Bal: <BAL>` (space added) | `Avl Bal:INR <BAL>` |
| Masked digits | `XX 12345` | `XX<D4>` | `XX <D5>` |
| Masked digits | `A/c XX12` | `A/c XX<D4>` | `A/c XX<D2>` |
| Date with spaces | `30 Sep 2026` | `<DATE>` | `<DATEW>` |

Amount (`Rs.<AMT>` keeps currency and spacing), ref, time, phone, handle and link placeholders
already follow the rule.

## 3. Changes to `Redactor`

1. **Balance:** keep the phrase, the currency token and its spacing; replace only the number.
   Capture the currency and the space in groups and emit `group1 + currency + space + "<BAL>"`.
   No `trimEnd()`, no inserted space. `Avl Bal Rs.15,342.50` → `Avl Bal Rs.<BAL>`. The number is
   what identifies someone; the word "Rs." doesn't.
2. **Masked digits** (both account rules): emit `<D{n}>` with `n` the number of digits actually
   masked, and keep whatever whitespace sat between the mask and the digits.
   `A/c XX1234` → `A/c XX<D4>` (unchanged), `XX 12345` → `XX <D5>`.
3. **Dates:** keep `<DATE>` for dates without spaces (`30-09-2026`, `30-Sep-26`), and emit a new
   `<DATEW>` for the month-name rule when the matched date contains whitespace
   (`30 Sep 2026`). A `\S+` date group can't read a spaced date, so the console needs to know.
   The console's placeholder syntax (`<[A-Z]+\d*>`) already accepts `DATEW`.
4. `unredactedHints` needs no change: none of these reintroduce digits or handles.

Side note, not blocking: the balance rule also fires on the Axis helpline line
`WhatsApp BAL to 917036165000` (→ `BAL to <BAL>`). It's still masked, just under the wrong
name. With the invariant below it keeps passing; tightening it is optional.

## 4. Invariant test (catches this class of bug for good)

For every message in the real-message corpus, the template must "fit back" over the original:

```kotlin
private fun fitsBack(original: String, template: String): Boolean {
    val regex = template.split(Regex("""(<[A-Z]+\d*>)"""))   // keep delimiters, e.g. via a manual tokenizer
        .joinToString("") { part ->
            if (Regex("""<[A-Z]+\d*>""").matches(part)) ".+?" else Regex.escape(part)
        }
    return Regex(regex, RegexOption.DOT_MATCHES_ALL).matches(original.trim())
}
```

(Kotlin's `split` drops delimiters, so tokenize with `Regex("""<[A-Z]+\d*>""").findAll` and
slice the text between matches.)

- Move the verbatim messages in `data/sms/RealMessageTest.kt` into a shared `RealMessages`
  object, so both that test and a new `RedactorInvariantTest` use them, and add the two TMB
  messages above plus a debit:
  `Your A/c No.XXXX0017 is debited with Rs.1,783.60 on 30-09-2026 10:37 AM and UTIB A/c linked to pinelabs.11093315@pineaxis is credited (UPI Ref No.130455129208).Current AVBL bal is Rs.5214.44 - TMB`
- Assert `fitsBack(message, Redactor.redact(message))` for all of them. This **fails today** on
  every message with `bal … Rs.<number>` and passes after §3.
- Also assert `<D{n}>` matches the digit count for a 2-, 4- and 6-digit masked number.

## 5. Re-check the whole review queue (§8.1)

- `SmsRepository.reparseNeedsReview`: drop `REPARSE_LIMIT` and page through every
  `NEEDS_REVIEW` row, newest first, 500 at a time, using keyset paging: a new DAO query like
  `WHERE parseStatus = :status AND (receivedAt < :beforeAt OR (receivedAt = :beforeAt AND id < :beforeId)) ORDER BY receivedAt DESC, id DESC LIMIT :limit`,
  continuing from the last row of the previous page until a page comes back short.
- Not `OFFSET`: rows that clear leave `NEEDS_REVIEW` mid-loop, so offsets would skip rows.
- Cost: the rule regexes are compiled once per sync; a few thousand rows is milliseconds of
  regex work. It already runs off the main thread with the sync.
- `ReparseOutcome.checked` then reports the whole queue, so Settings → Check now says
  "Re-checked 728 pending messages, cleared N".
- Test: 250 queued rows, oldest one matches a newly synced rule → it's cleared.

## 6. Rollout and compatibility

- New templates from updated phones look different (`bal is Rs.<BAL>`), so the console will
  show them as a new group next to the old one. That's fine; resolve or delete the old group.
- Templates from older app versions stay lossy. The console compensates: its synthetic samples
  vary the balance (`18,402.55`, `Rs.6,210.00`, `INR 2,044.10`) and digit counts where the
  template can't be trusted, and the rule editor has a local "test a real message" box.
- No change to the rules schema or Firestore.

## 7. Checklist

- [x] §3 redactor changes, with `RedactorTest` expectations updated (`Avl Bal Rs.<BAL>`).
- [x] §4 `RealMessages` corpus + `RedactorInvariantTest` (red before §3, green after).
- [x] §5 full-queue re-parse with keyset paging + test with more than 200 rows.
- [x] REQUIREMENTS.md §6.1 (the invariant, `<D{n}>`, `<DATEW>`) and §8.1 (no cap).
- [x] §8 `<NUM>` catch-all + narrowed `<PHONE>`, so no real message is unsubmittable.
- [ ] Release; on the phone, Check now after the next rules version clears old TMB messages.

## 8. Two real messages that couldn't be submitted at all — fixed

Found while doing §4, fixed after it. Both left a bare digit run, so `unredactedHints` flagged them
and §6.1.1 refused the upload — the review queue showed "Not sent — the message still shows a long
number after redaction" for exactly the two messages most in need of a rule. Nothing was marked
submitted on that path, so no rows needed repairing.

| Message | Was | Now |
|---|---|---|
| `axisApy` | `APY/<PHONE>/920010018` | `APY/<NUM>/<NUM>` |
| `npsDebit` | `MUM-HDFCH00842011992-NET BANKING` | `MUM-HDFCH<NUM>-NET BANKING` |

1. **`<NUM>` catch-all**, last in `RULES` on purpose: any `\d{5,}` run none of the rules above
   recognised. It shares `unredactedHints`' threshold, so "a long number" is now unreachable for
   anything `redact` produces. It has to sit *after* the handle rule — run earlier and it breaks
   `pinelabs.11093315@pineaxis` apart before `<VPA>` matches it.
2. **Narrowed `<PHONE>`** to `(?:\+?91)?[6-9]\d{9}` or `1800\d{6,7}`. The old `\d{10,12}` called the
   Axis mandate id a phone number, which misleads a rule author the same way the old balance shape
   did. All four helpline numbers in the corpus still read as `<PHONE>`.

`HDFCH` is kept because it prefixes every HDFC NEFT reference: that's shape, and it gives a rule
something to anchor on (`HDFCH(?<ref>\d+)`).

Cost, accepted knowingly: the catch-all is blunt. A 5+ digit run that was useful anchor text gets
masked too. It fails safe — masked, never leaked — but the invariant only proves a template still
fits its message, not that it's still useful to write a rule against.

Console side: `<NUM>` is a new placeholder name, so its synthetic-sample generation needs to know
about it, as `<DATEW>` did.
