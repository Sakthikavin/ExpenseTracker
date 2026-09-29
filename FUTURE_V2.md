# Future — v2 Family Sharing & Authentication

Design notes for the not-yet-built v2. v1 is fully local — no accounts, no network — per
[ARCHITECTURE.md](ARCHITECTURE.md). This doc captures the plan for when that changes, extracted
from the original pre-build spec (now superseded for everything already shipped).

---

## Family sharing

**Backend:** Firebase — Firestore for data, Firebase Auth for Google sign-in.

**Why Firebase over Supabase:** offline sync and conflict resolution ship in the client SDK,
which is the hard part of a two-phone app parsing SMS on patchy mobile data. Supabase's
Postgres and lack of lock-in are appealing, but its offline story needs hand-rolling and its
free projects pause after a week idle — a real failure mode for an app that may sit unused
during a holiday.

**Cost:** free permanently at this scale. Firestore's Spark tier gives 1 GiB storage and
50k reads / 20k writes per day; two people generating a few hundred transactions a month
is a rounding error. Staying on Spark also means no billing surprises — it hard-stops at
quota rather than charging, and needs no card on file. (Blaze, by contrast, has no spending
ceiling.)

**Shape:**

- Each member installs the app and signs in; each phone parses its own SMS locally
- Transactions sync to a shared household space
- Dashboard toggles Mine / Family — combined totals, per-person split, per-category breakdown
- Household budgets layered over personal ones
- Invite by email; recipient accepts to join
- **Private flag** per transaction keeps anything marked private off the shared view

The private flag is confirmed in scope. It is not a nicety — an expense app that exposes
everything to a spouse by default becomes a surveillance tool and gets abandoned.
Enforcement is **UI-level only**: with two trusted users the server-side case is weak. Note
the consequence — a determined household member querying Firestore directly could still
read private rows. Acceptable here by decision, not by accident.

**Modelling note:** Firestore rewards denormalising for read patterns rather than
normalising. Household document with transactions as a subcollection; category names
duplicated onto each transaction rather than joined. This is where Postgres instincts
mislead.

**`householdId` / `userId` / `isPrivate` already exist in every table**, inert in v1 by
design — see [data/local/entity/Ids.kt](app/src/main/java/com/example/expensetracker/data/local/entity/Ids.kt)
— so this is meant to land additively, not as a schema rewrite.

### Authentication

**Approach:** Android Credential Manager + Google Identity Services, exchanging the
resulting Google ID token with Firebase Auth. This is the current production-standard
Android sign-in path (it supersedes the deprecated Google Sign-In SDK), and it keeps the
ID token visible so the JWT — claims, audience, issuer, expiry — can be inspected rather
than hidden behind a drop-in UI.

Terminology: this is **federated identity** over OAuth 2.0 / OIDC, not SSO in the
enterprise sense. SSO proper means one identity spanning multiple independent services,
typically SAML or OIDC against a corporate IdP.

**Cost:** Google sign-in is free to 50,000 monthly active users. Phone/SMS sign-in is
never free and is not used.

**Where the real work is — authorization, not authentication.** Firestore Security Rules
are the load-bearing piece:

- Household reads and writes gated on `request.auth.uid` being a member of that household.
  This one is non-negotiable regardless of user count: Firestore is reachable directly from
  the internet with no server of yours in front of it, and the project ID ships inside the
  APK. Test-mode rules leave the database world-readable.
- The `isPrivate` flag is **not** enforced in rules — UI filtering only, by decision.
- Rules tested against the emulator before trusting them.

**Setup that must be done by hand** (tied to a Google account, can't be scripted):

1. Create a Firebase project in the console
2. Register the Android app and add the debug/release SHA-1 signing fingerprint
3. Enable Google as a sign-in provider
4. Download `google-services.json` into the app module

### Build order (even though it ships as one version)

Building it in stages keeps a failure traceable across four new subsystems at once:

1. Auth alone, no Firestore. Sign in, decode the ID token to logcat, sign out.
2. Firestore with authenticated-but-loose rules. Prove sync works.
3. Tighten rules to `request.auth.uid` and verify against the emulator.

*Gotcha:* mismatched debug vs release SHA-1 fingerprints is the classic sign-in failure.

Anonymous auth is deliberately skipped — it existed only as a stepping stone in earlier
plans, and account linking was only ever needed to rescue the data it stranded. With no
anonymous phase there is nothing to rescue.

---

## v3 — Family sharing rollout (after auth + sync land)

- Household entity, invite by email, accept flow
- Dashboard toggles Mine / Family — combined totals, per-person split
- Household budgets layered over personal ones
- Private flag hides transactions from the shared view (UI-level; see note above)
- Rules gate every household read on membership

*Gotcha:* rules that perform document lookups bill extra reads and slow down — denormalise
household membership onto the documents instead.

---

## Decisions log (carried forward from the original spec)

- Default category: **unassigned**. No starter category set.
- Budgets: **no rollover** of unused amounts between months.
- Private flag: enforced at **UI level only**. With two trusted users the server-side case
  is weak, so it stays a filter rather than a rules constraint.
- Household access rules: still enforced server-side. Firestore is reachable directly from
  the internet and the project ID ships inside the APK, so "only two users" describes who
  was invited, not who can reach the endpoint. Test-mode rules would leave the database
  world-readable regardless of user count.

## Explicitly out of scope (from the original spec, still holds)

| Dropped | Reason |
|---|---|
| Pay Later, personal loans, fixed deposits | Requires NBFC licensing and banking partnerships |
| Live bank balance window | No lawful API path without RBI Account Aggregator FIU registration |
| Ads and upsells | Personal app |
| Credit card as a distinct account type | Accounts are plain labels; bank identity doesn't matter |
| Play Store publication | `READ_SMS` is restricted by Play policy; sideload instead |
