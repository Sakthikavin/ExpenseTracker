# Notes for the console repo

What changed on the device that the console can't see from the rules schema, written after
implementing `BANK_RULES_FIRST.md`. Nothing here changes the published JSON — it's all about what
the console shows rule authors, what it warns them about, and what it can no longer infer.

Ordered by how much it bites.

## 1. `appVersion` can't date a template — fixed going forward, not backward

Every submission carries `appVersion` (`SubmissionRepository.kt:53`), set from
`BuildConfig.VERSION_NAME` (`di/AppContainer.kt:57`). That was `"1.0"` from the first release and
never bumped, so every submission sent so far looks the same age.

**Now bumped** to `versionName = "1.1"` / `versionCode = 2`, and the comment in
`app/build.gradle.kts` says to move both with every release and keep them in step with the git tag.
From the next release on, `appVersion` is a real signal.

It says nothing about submissions already in the console, and nothing about phones that haven't
updated — a sideloaded app updates when its owner gets round to it. So the retirement signal is
"no submissions reporting `1.0` for long enough to be sure", and until then the extra sample
variations for old templates have to stay. That's the right call.

This undercuts the compensation strategy in `FAITHFUL_REDACTION.md` §6 — vary the balance and the
digit counts "for templates from older app versions" — because there is no signal for *which*
version a template came from. It now matters for five placeholders rather than one: `<BAL>`,
`<D`*n*`>`, `<NUM>`, `<DATEW>` and `<CODE>`. `rulesVersion` is the only other hint and it's a weak
lower bound: it says which rule set the phone had, not which redactor built the template.

The fix is app-side (bump `versionName` every release). Until it lands, treat template shape itself
as the only evidence of age — `bal is <BAL>` without a currency token means an old build, and so
does a code sitting unmasked in a template.

## 2. Priority 5 is now load-bearing, not just a sort key

`SmsParser.parse` splits the remote-rule walk around the built-in templates
(`REQUIREMENTS.md` §5 steps 3–5): `priority >= 5` runs before them, `priority < 5` after.
`BANK_RULE_MIN_PRIORITY = 5` in `RemoteRulesRepository`.

So a bank-specific rule published at 1–4 silently loses to the template for every message the
template reads at all. It will look like it does nothing, with no error anywhere — the exact
failure the change was meant to end.

Worth a publish-time warning: a rule that isn't an `android_*` generic copy and sits below priority
5 is almost certainly a mistake. And the `android_*` copies need to **stay** at 1–4; promoting one
to 5 would put a plainer parse ahead of the richer in-app template on every bank.

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

## 5. `impact.js` is now optimistic for rules below priority 5

It simulates remote rules alone, in priority order, which still matches the device for each half of
the walk. What it can't see is `BankTemplates` sitting between priority 5 and 4. Any group it
predicts a fallback-priority rule will gain is an over-estimate, because on the device the
templates answer first for anything they can read.

Either model the templates or show the caveat on that side of the threshold. Above 5 the prediction
is exact.

## 6. Expect non-bank noise in `/submissions`

The app used to drop a message no tier recognised without storing anything. It now keeps anything
that mentions money as `ParseStatus.DISCARDED`, shows it under Settings → "Messages I skipped", and
offers **Move to review** — from where it can be submitted. So a voucher, a promo or an order
update can now reach the console in a way it previously couldn't.

Nothing in the payload distinguishes that path from a genuine review-queue submission. Adding a
field is possible but not free: `firestore.rules` validates the exact document shape, so the rules
change has to be deployed *before* any phone starts sending the field, or the creates get rejected.

The existing route for over-admission is unchanged and is still the right one: an ignore rule
(`IGNORE_RULES.md`), not an app release.

## What the app side owes the console

- [x] Bump `versionName` per release so §1 stops being true — done, `1.1` / `versionCode 2`, with
      the standing instruction recorded at the bump itself.
- [ ] Decide whether a submission says which queue it came from (§6). **Agreed with the console:
      the app does not add that field without saying so first**, because `firestore.rules` has to
      deploy before any phone sends it or those submissions are rejected.
