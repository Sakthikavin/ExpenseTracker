# Notes for the console repo

What changed on the device that the console can't see from the rules schema, written while
implementing `BANK_RULES_FIRST.md` and then `PARSING_ARCHITECTURE.md`. Nothing here changes the
published JSON — it's all about what the console shows rule authors, what it warns them about, and
what it can and can no longer infer.

Ordered by how much it bites.

## 1. `appVersion` can't date a template — fixed going forward, not backward

Every submission carries `appVersion` (`SubmissionRepository.kt:53`), set from
`BuildConfig.VERSION_NAME` (`di/AppContainer.kt:57`). That was `"1.0"` from the first release and
never bumped, so every submission sent so far looks the same age.

**Now bumped** to `versionName = "1.1.0"` / `versionCode = 2`, and the comment in
`app/build.gradle.kts` says to move both with every release and keep them in step with the git tag.
From the next release on, `appVersion` is a real signal.

It says nothing about submissions already in the console, and nothing about phones that haven't
updated — a sideloaded app updates when its owner gets round to it. So the retirement signal is
"no submissions reporting `1.0` for long enough to be sure", and until then the extra sample
variations for old templates have to stay. That's the right call.

Why it matters: the compensation strategy in `FAITHFUL_REDACTION.md` §6 — vary the balance and the
digit counts "for templates from older app versions" — needs to know *which* version a template came
from, and it now applies to five placeholders rather than one: `<BAL>`, `<D`*n*`>`, `<NUM>`,
`<DATEW>` and `<CODE>`. `rulesVersion` is the only other hint and it's a weak lower bound: it says
which rule set the phone had, not which redactor built the template.

For everything already submitted, template shape is the only evidence of age — `bal is <BAL>`
without a currency token means an old build, and so does a code sitting unmasked in a template.

## 2. Priority is now the *only* ordering — and the app has no templates left

Superseded in the app before release, in the direction the console had already taken: there is no
priority-5 split and no `BankTemplates`. `SmsParser` walks one list, by priority, and nothing of the
app's own reads a message in between. Two consequences for the console:

- **Nothing on the device backs up a missing rule.** A sender no rule reads produces no transaction
  at all — the message waits in the review queue instead. That makes the generic `"*"` rules
  load-bearing in a way they weren't when they merely duplicated the templates: publishing a set
  without them would stop every phone parsing anything generic. The publish check should refuse, or
  at least warn hard, on a set with no `"*"` rule.
- **Priority ordering is exactly what the device does**, so `impact.js` is now a faithful simulation
  rather than an approximation (see §5).

Still worth a warning on a bank-specific rule published below the generic band (1–6): it will be
tried after rules that are meant to be fallbacks, which is almost certainly a mistake.

## 3. No existing rule broke — rules match the raw SMS, not the template

Worth saying plainly so nobody republishes the rule set out of caution. A template is only what a
rule author is *shown*; on the device `applyRule` runs the published pattern against the raw message
body. The `BAL to <BAL>` → `BAL to <PHONE>` change therefore affects authoring, not matching.

`axisbk_debit_v1` (v13, v14) captures `917036165000` as the balance because the template told its
author that's what it was. Still worth rewriting, but the damage is bounded: `applyRule` requires
every `fieldMap` group to be non-empty and then never reads the balance's value
(`RemoteRulesRepository.applyRule`), so the rule books the right amount, direction and merchant and
the mis-captured phone number is discarded on device, never stored.

Keep the "captures `BAL to …` as the balance" publish warning until old app versions are gone —
templates already in the console still carry the old shape, and so do submissions from phones that
haven't updated.

## 4. `<CODE>` is a new placeholder

`Redactor` now masks a claimable code — voucher, coupon, OTP, PIN — as `<CODE>`, keeping the label
and separator (`Code: 346QH2VK` → `Code: <CODE>`, `OTP is 4821` → `OTP is <CODE>`). The console's
`<[A-Z]+\d*>` syntax already accepts it, but three things need to learn it, the same way they
learned `<NUM>` and `<DATEW>`: template grouping, the starter-regex builder, and synthetic-sample
generation.

`unredactedHints` in `lib/grouping.js` needs the matching check for inbound submissions:
`code`/`coupon`/`otp`/`pin`, optional `is`/`:`/`-`, then an alphanumeric run of 4+ containing a
digit. Deliberately looser than the masking rule's 12-character ceiling — a longer code the rule
leaves alone must still be refused rather than accepted.

## 5. `impact.js` now predicts the device exactly

It simulates remote rules alone, in priority order — which, with the templates gone and the walk
back to one list, is precisely what `RemoteRulesRepository.tryMatch` does. The caveat this section
used to carry is void: there is nothing left on the device that reads a message between two rules.

Two things it should model that the device does, if it doesn't already: `"*"` senders match every
sender, and a rule falls through when any mapped `fieldMap` group comes back empty.

## 6. Expect non-bank noise in `/submissions`

The app used to drop a message no tier recognised without storing anything. It now keeps anything
that mentions money as `ParseStatus.DISCARDED`, shows it under Settings → "Messages I skipped", and
offers **Move to review** — from where it can be submitted. So a voucher, a promo or an order
update can now reach the console in a way it previously couldn't.

Nothing in the payload distinguishes that path from a genuine review-queue submission. Adding a
field is possible but not free: `firestore.rules` validates the exact document shape, so the rules
change has to be deployed *before* any phone starts sending the field, or the creates get rejected.

The existing route for over-admission is unchanged and is still the right one: an ignore rule
(`IGNORE_RULES.md`), not an app release. It now carries more weight than before: duplicate payments
and self-transfers are also console work, since the app no longer matches references or pairs
transfer legs (`PARSING_ARCHITECTURE.md` §7).

## 7. Eight live bank rules capture no merchant

Of the 18 bank rules in v16, eight map no `merchant` group: `hdfcbk_debit_v1`, `sbibnk_debit_v1`,
`cbssbi_debit_v1`, `axisbk_debit_v1`, `axisbk_credit_v1`, `tmbank_credit_v2`, `myjptr_credit_v1`,
`onjptr_credit_v1`. They parse, so the amount and direction are right, but the transaction shows as
"(no merchant)" and the merchant→category learning has nothing to key on — so those spends can never
be auto-categorised.

This mattered less when a built-in template might read the same message and find a payee. Nothing
does now. Worth surfacing in the console: a rule with an `amount` but no `merchant` group is
incomplete rather than finished.

## 8. Still unpublished, and visible in the corpus

The HDFC NEFT narration (`Info: NEFT Dr-…-NPS Contribution M`) has no rule, so its merchant reads
"HDFC Bank XX3941" — the app used to guess "NPS Contribution" out of that narration and no longer
does. `PARSING_ARCHITECTURE.md` §4 carries a tested regex for it. Canara's `Dr.`/`Cr.` alerts still
reach nobody's rule and sit in review.

## What the app side owes the console

- [x] Bump `versionName` per release so §1 stops being true — done, `1.1.0` / `versionCode 2`, with
      the standing instruction recorded at the bump itself.
- [ ] Decide whether a submission says which queue it came from (§6). **Agreed with the console:
      the app does not add that field without saying so first**, because `firestore.rules` has to
      deploy before any phone sends it or those submissions are rejected.
