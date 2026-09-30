# TODO

Agreed work not yet started. Design docs own the detail; this file owns the "not done yet".
Spec-level backlog lives with its spec — see `data/remoterules/FAITHFUL_REDACTION.md` §7–§8 and
[FUTURE_V2.md](FUTURE_V2.md) for v2 family sharing.

## Search by message text — Transactions tab and Review tab

Find a transaction or a queued message by typing words that appear in **the raw SMS**, not just
the merchant name. The message text is what the user actually remembers ("that Swiggy one with the
weird ref"), and it's the only searchable handle on a review-queue row, which has no merchant or
amount parsed out of it yet.

- **Review tab** — matches `raw_sms.body` (and probably `sender`) of the rows already on screen.
- **Transactions tab** — the body lives one hop away, on the `raw_sms` row a transaction points at
  via `rawSmsId`, and is loaded lazily per row today (`OriginalMessagePanel`). Searching it needs a
  query that reaches the message from the transaction, not the current per-row lazy load.
- Manually added transactions have no raw SMS, so decide whether they match on merchant/notes only
  or drop out of a text search entirely.
- Worth checking whether `LIKE '%term%'` over a few thousand rows is fast enough before reaching
  for an FTS table (an FTS index is a schema change, so it needs a Room migration).
