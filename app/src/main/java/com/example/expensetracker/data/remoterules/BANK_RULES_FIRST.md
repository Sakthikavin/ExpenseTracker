# Bank rules before built-in templates, and three redaction fixes

Android-side spec. Companion to `REQUIREMENTS.md` (§5, §5.2, §6.1) and `FAITHFUL_REDACTION.md`
in this folder. Fold the changes into REQUIREMENTS.md when you implement, so the two repos' specs
stay identical.

**Implemented** on the app side (see §6 for what's left, all console-side or on-device checks).
REQUIREMENTS.md §5/§5.2/§6.1 and `FAITHFUL_REDACTION.md` §3/§7 now carry the same changes.

## 1. Why: a bank rule can't fix a message the generic template reads badly

`SmsParser.parse` runs `BankTemplates.findMatch` before `remoteRulesRepository.tryMatch`, whatever a
remote rule's priority. A FEDBNK UPI debit:

```
Rs 12000 debited via UPI on 01-09-2026 09:05 to VPA landlord.ravi@ybl No 427100394821.Small txns?Use UPI Lite!-Federal Bank
```

The generic Federal template reads it, with merchant `VPA landlord` (it stops at the dot in the
handle). `looksLikeAccountOrNumber` accepts that, because it has letters. A reviewed FEDBNK rule on the console
reads `landlord.ravi@ybl`, but it never runs, because the template answered first. Same for
`VPA kumarstores@okaxis No 427381920113` as a merchant.

The console publishes the four generic templates as remote rules too (`android_*_v1`). Since v14
they sit at **priority 4–1**; bank-specific rules use 10 and up.

## 2. The new order

```
ALWAYS_IGNORE → discardSenders → ignoreRules
→ remote rules with priority ≥ 5, highest first        ← new: reviewed, bank-specific
→ BankTemplates.findMatch                              ← unchanged: generic, but richer
→ remote rules with priority < 5 (the generic copies)
→ learned patterns → pre-notice → review
```

Why not simply all remote rules first: the in-app templates do more than read a message. They
resolve self-transfers and the counterparty institution (`resolveNonMerchant`,
`COUNTERPARTY_ACCOUNT`, used by `TransferMatcher`), and read block-format alerts. A remote rule
yields only amount, direction and merchant. Running the generic `android_*` copies ahead of the
templates would swap that richer parse for a plainer one on every bank. Priority 5 is the line:
**1–4 is reserved for generic fallbacks** (console convention, recorded in both REQUIREMENTS).

Implementation:

- `RemoteRulesRepository.tryMatch(sender, body, minPriority: Int = Int.MIN_VALUE, maxPriority: Int = Int.MAX_VALUE)`,
  filtering `compiled` by priority before the existing sort. Or two named functions,
  `tryBankRules` / `tryFallbackRules`, with the threshold as a constant `BANK_RULE_MIN_PRIORITY = 5`.
- `SmsParser.parse`: bank rules, then `BankTemplates.findMatch`, then fallback rules.
- `reparseNeedsReview` needs no change; it calls `parse`.

Tests (`SmsParserTest` or a new one):

- The FEDBNK message above, with a remote FEDBNK rule at priority 10 reading
  `to\s+VPA\s+(?<merchant>\S+)\s+No` → merchant `landlord.ravi@ybl`, not `VPA landlord`.
- The same message with the rule at priority 3 → the template's result (unchanged behaviour).
- A self-transfer the templates resolve today (`… To ICICI Bank A/C XX4795 …`) with no bank rule
  → still resolved by the template, not by an `android_*` copy at priority 4.
- A message only an `android_*` copy reads (the template doesn't) → still parsed, via the
  fallback pass.

The console's publish check (`impact.js`) simulates remote rules only, in priority order, so it
already predicts this order for everything except the in-app templates' extra reading. No console
change needed.

## 3. Redaction fix: voucher codes, OTPs and PINs

`Congrats! Rs.1,250.00 voucher … Code: 346QH2VK. Claim: <link> T&C apply` uploads the code
verbatim: it has no run of 5+ digits, so neither `<NUM>` nor `unredactedHints` sees it. A voucher
code is claimable; an OTP or PIN is worse.

Add a rule right after `URL` in `Redactor.RULES`:

```kotlin
// Codes someone could use: vouchers, coupons, OTPs, PINs. Only alphanumeric runs with a digit,
// so "Code: apply" or "PIN changed" keep their words.
Regex("""(?i)\b(code|coupon|otp|pin|passcode)(\s*(?:is|:|-)?\s*)(?=[A-Za-z]*\d)([A-Za-z0-9]{4,12})\b""") to
    { m -> m.groupValues[1] + m.groupValues[2] + "<CODE>" },
```

- `Code: 346QH2VK` → `Code: <CODE>`; `OTP is 4821` → `OTP is <CODE>`; `Your PIN has been changed`
  unchanged.
- It must run before `<NUM>` (a 6-digit OTP would otherwise become `<NUM>`, which hides it too but
  names it wrongly) and before the ref rule.
- `unredactedHints`: add `a code` when `(?i)\b(?:code|coupon|otp|pin)\b\s*(?:is|:|-)?\s*(?=[A-Za-z]*\d)[A-Za-z0-9]{4,}`
  finds something outside placeholders. The console adds the same check and knows `<CODE>`.
- Corpus: add the voucher message to `RealMessages`; `RedactorInvariantTest` covers it.

## 4. Redaction fix: "WhatsApp BAL to <number>" is a helpline, not a balance

Axis alerts end `WhatsApp BAL to 917036165000 Query? Call 18604195555`. The balance rule's
`(?:bal|balance)\b[^\d]{0,12}?` reads `BAL to 917036165000` as a balance, so the template says
`BAL to <BAL>`, and the live rule `axisbk_debit_v1` (v13, v14) captured `917036165000` as the
balance. (Side note in `FAITHFUL_REDACTION.md` §3; this is where it bit.)

- Balance rule: add `(?!\s+to\b)` after `(?:bal|balance)\b`.
- The number then reaches the `<PHONE>` rule: `91` + a mobile starting 7 → `WhatsApp BAL to <PHONE>`.
- Test: that Axis line → `WhatsApp BAL to <PHONE>`; `Avl Bal Rs.1,234.00` still → `Avl Bal Rs.<BAL>`.

The console already warns when a rule captures `BAL to …` as the balance, for templates from
older app versions.

## 5. Observed, not in scope

`RemoteRulesRepository.applyRule` builds `ParsedSms` from amount, direction and merchant only;
`occurredAt` comes from `SmsDateParser.parse(body)`. A rule's `date`, `account`, `ref` and
`balance` groups are required to be non-empty but their values are never used. That's fine as a
safety check (a rule that matched the wrong text usually leaves one empty), but if you want the
rule's date to win over `SmsDateParser`, or the balance stored, that's a separate change.

## 6. Checklist

- [x] §2 order: bank rules (≥ 5) → templates → fallback rules (< 5), with the four tests
      (`RulePriorityOrderTest`). Implemented as `tryBankRules` / `tryFallbackRules` over a private
      `tryMatch(sender, body, priority)`, with `BANK_RULE_MIN_PRIORITY = 5`.
- [x] §3 `<CODE>` rule + `unredactedHints` + corpus message (`RealMessages.voucherCode`, which is
      a reconstruction of the shape recorded here, not a capture from the phone — replace it if the
      original turns up). Note the exposure is narrower than §3 implies: uploads only happen from
      the review queue, and this message is `Discarded`, so it can only be uploaded after a manual
      **Move to review** from the skipped list.
- [x] §4 balance rule ignores `BAL to`; Axis helpline → `<PHONE>`. Also applied to
      `BankTemplates.BALANCE_STATEMENT`, which §4 didn't mention: reading the helpline as a balance
      gave every Axis message a bank-alert marker, so a promotion quoting an amount reached the
      review queue on structure alone (`BankTemplatesTest`).
- [x] REQUIREMENTS.md §5 (order, priority 1–4 reserved), §5.2, §6.1 (`<CODE>`, `BAL to`), §6.1.1
      (the code check), and `FAITHFUL_REDACTION.md` §3's side note closed.
- [ ] Release, then Settings → Check now: FEDBNK UPI debits in review pick up the bank rule.
- [ ] Console: publish the FEDBNK rule at priority ≥ 5, and add `<CODE>` to the submission check
      and the synthetic-sample generator (§3). The full console-side list, including what the
      console can no longer infer, is in `CONSOLE_HANDOFF.md`.
