# Expense Tracker — Feature Spec & Architecture

Personal Android expense tracker, inspired by axio (formerly Walnut) but stripped of its
lending business and cloud-account requirement.

Status: **spec agreed, no code written yet**

---

## 1. Scope

### In scope for v1

| Feature | Notes |
|---|---|
| SMS auto-detection of transactions | Debit and credit alerts parsed into transactions automatically |
| "Needs review" queue | Unparsed transactional SMS surfaces for manual confirmation |
| Learned patterns | Confirming a reviewed SMS teaches the parser that bank's format |
| Manual entry | Cash spends, and any bank whose SMS never arrives (e.g. TMB) |
| Income tracking | Credit alerts logged as income, kept distinct from expenses |
| Categories | Default set plus user-defined; tags and free-text notes |
| Receipt photos | Attach an image to any transaction |
| Dashboard | Monthly/daily totals, spend-by-category chart, income vs expense |
| Budgets | Monthly limit per category, warning as you approach it |
| Bill reminders | Recurring bills with local notifications |
| Search & filter | By category, tag, date range, amount, account |
| CSV export | Local file, no email round-trip |

### Explicitly out of scope

| Dropped | Reason |
|---|---|
| Pay Later, personal loans, fixed deposits | Requires NBFC licensing and banking partnerships |
| Live bank balance window | No lawful API path without RBI Account Aggregator FIU registration |
| Cloud account / mandatory login | v1 is on-device; login arrives only with family sharing |
| Ads and upsells | Personal app |
| Credit card as a distinct account type | Accounts are plain labels; bank identity doesn't matter |
| Play Store publication | `READ_SMS` is restricted by Play policy; sideload instead |

### Deferred to v2

- Family sharing (see §5)
- Authentication — federated Google sign-in (see §5.1). v1 stays fully local with no
  sign-in screen; auth arrives together with sync.

---

## 2. The TMB problem

TMB does not send transaction SMS to the device at all. No parser can recover a message
that never arrives, so TMB transactions are **manual entry only**. This is a data-source
limitation, not something to engineer around.

For banks that *do* send SMS but in an unrecognised format, the review queue plus learned
patterns handles it — see §3.

---

## 3. SMS parsing design

Three-tier approach, degrading gracefully:

1. **Known templates.** Built-in regexes for common Indian bank formats — HDFC, SBI, ICICI,
   Axis, Kotak, plus generic UPI alerts. Matches sender ID and message body.
2. **Review queue.** An SMS that looks financial (contains a currency amount plus
   debit/credit keywords) but matches no template is stored as unparsed and surfaced in a
   "Needs review" list. You confirm amount, merchant and direction.
3. **Learned patterns.** Confirming a reviewed message derives a reusable pattern for that
   sender, persisted locally. The next message from that bank parses on its own.

This is the main functional advantage over axio, which silently drops what it can't parse —
the single most common complaint in its Play Store reviews.

### Permission reality

- `RECEIVE_SMS` / `READ_SMS` work normally on a sideloaded app.
- Google Play restricts these permissions to default SMS handlers or apps that pass a
  declaration review. axio qualifies under the "SMS-based financial transactions" exception,
  backed by its NBFC status. We do not intend to publish, so this doesn't constrain us —
  but publishing later would mean dropping SMS parsing.

---

## 4. Architecture

**Stack:** Kotlin, Jetpack Compose, MVVM, Room (SQLite), Coroutines + Flow, WorkManager,
a `BroadcastReceiver` for incoming SMS.

**Locale:** INR formatting, Indian bank SMS templates.

```
UI (Compose screens)
  └─ ViewModels (StateFlow)
       └─ Repository
            ├─ Room DAOs  ──────────── local SQLite (source of truth in v1)
            └─ SmsParser  ──────────── templates + learned patterns
                 └─ SmsReceiver (BroadcastReceiver)
```

### Data model

Household and user IDs are present from day one so that adding sync in v2 is not a rewrite.
In v1 they hold a single local user.

- **Transaction** — id, householdId, userId, amount, direction (debit/credit), occurredAt,
  merchant, accountLabel, categoryId, note, tags, receiptPath, source (sms/manual),
  isPrivate, rawSmsId
- **Category** — id, householdId, name, icon, colour, isIncome
- **Budget** — id, householdId, categoryId, monthlyLimit, period
- **Bill** — id, householdId, name, amount, dueDay, recurrence, lastNotifiedAt
- **RawSms** — id, sender, body, receivedAt, parseStatus, linkedTransactionId
- **LearnedPattern** — id, senderPattern, regex, fieldMap, confirmedCount

`isPrivate` and the household/user IDs are dead weight in v1 by design — they're the
seams that let family sharing land later without a migration.

---

## 5. Family sharing (v2)

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

### 5.1 Authentication

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
- The `isPrivate` flag is **not** enforced in rules — UI filtering only, by decision (§8)
- Rules tested against the emulator before trusting them

**Setup you must do yourself** (tied to your Google account, cannot be scripted here):

1. Create a Firebase project in the console
2. Register the Android app and add your debug/release SHA-1 signing fingerprint
3. Enable Google as a sign-in provider
4. Download `google-services.json` into the app module

---

## 6. Build and install

The sandbox has no network access to Google's Android SDK servers, so the APK cannot be
compiled here. The full project gets written out, then:

1. Open the project folder in Android Studio
2. Let Gradle sync (downloads SDK and dependencies)
3. Build → Build APK, or run directly on the phone over USB with debugging enabled
4. For sideloading a built APK: enable install from unknown sources, transfer, tap to install

---

## 7. Release roadmap

Four versions, each shippable and each teaching one new layer.

### v1 — Fully offline

No Firebase, no network, no account. A working expense tracker on the phone.

- Room schema, Compose UI, MVVM, Coroutines/Flow
- SMS `BroadcastReceiver`, template parser, review queue, learned patterns
- Manual entry, income via credit SMS, categories, tags, notes, receipt photos
- Dashboard with category chart, budgets, bill reminders, search, CSV export
- `householdId` / `userId` / `isPrivate` present but inert — the seams for later

*Learn:* Kotlin, Compose, Room, Flow, BroadcastReceiver, WorkManager, runtime permissions.

*Gotchas:* runtime SMS permission flow; `POST_NOTIFICATIONS` is a runtime permission on
Android 13+; Doze mode will delay WorkManager reminders unless handled.

### v2 — Firebase and authentication together

Real identity and cloud sync in one release. Anonymous auth is skipped entirely — it
existed only as a stepping stone, and account linking was only ever needed to rescue the
data it stranded. With no anonymous phase there is nothing to rescue.

- Firebase project, `google-services.json`, SHA-1 fingerprint registered
- Credential Manager + Google Identity Services; Google ID token exchanged with Firebase Auth
- Inspect the JWT: claims, issuer, audience, expiry. Token refresh, sign-out
- Firestore mirrors Room; Room stays the source of truth (offline-first)
- Security rules scoped to `request.auth.uid`, tested against the Firebase emulator suite

*Learn:* OAuth 2.0 / OIDC, ID tokens, Credential Manager, Firestore modelling and
denormalisation, offline persistence, security rules, sync and conflict basics.

**Build in stages, even though it ships as one version** — otherwise a failure is
untraceable across four new subsystems:

1. Auth alone, no Firestore. Sign in, decode the ID token to logcat, sign out.
2. Firestore with authenticated-but-loose rules. Prove sync works.
3. Tighten rules to `request.auth.uid` and verify against the emulator.

*Gotcha:* mismatched debug vs release SHA-1 fingerprints is the classic sign-in failure.

### v3 — Family sharing

- Household entity, invite by email, accept flow
- Dashboard toggles Mine / Family — combined totals, per-person split
- Household budgets layered over personal ones
- Private flag hides transactions from the shared view (UI-level; see note below)
- Rules gate every household read on membership

*Learn:* multi-tenant modelling, invite flows, membership-aware security rules.

*Gotcha:* rules that perform document lookups bill extra reads and slow down — denormalise
household membership onto the documents instead.

---

## 8. Decisions log

- Default category: **unassigned**. No starter category set.
- Budgets: **no rollover** of unused amounts between months.
- Private flag: enforced at **UI level only**. With two trusted users the server-side case
  is weak, so it stays a filter rather than a rules constraint.
- Household access rules: still enforced server-side. Firestore is reachable directly from
  the internet and the project ID ships inside the APK, so "only two users" describes who
  was invited, not who can reach the endpoint. Test-mode rules would leave the database
  world-readable regardless of user count.