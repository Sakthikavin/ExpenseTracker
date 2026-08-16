#!/usr/bin/env bash
# Sends a batch of fake bank SMS to the running Pixel_9a emulator, covering:
#   - one message per default category (auto-parsed, lands in Transactions as
#     "Unassigned" — categorize by hand to check each one)
#   - a self-account transfer (two legs, shared reference) — should pair
#     automatically and NOT count as spend or income
#   - a few messages that are financial-looking but can't be tier-1 parsed —
#     these should land in the Review tab instead of Transactions
#
# Prereqs: emulator running, adb on PATH (see TESTING.md, step 0). Run from
# anywhere; only talks to the emulator over adb, doesn't touch the repo.

set -uo pipefail

send() {
  local sender="$1" body="$2"
  echo "-> [$sender] $body"
  adb emu sms send "$sender" "$body"
  sleep 0.3
}

echo "== Food & Dining =="
send HDFCBK "Rs 450.00 debited to SWIGGY on 16-08-26. Ref 778801. -HDFC Bank"

echo "== Groceries =="
send HDFCBK "Rs 1,240.00 debited to BIGBASKET on 16-08-26. Ref 778802. -HDFC Bank"

echo "== Transport =="
send HDFCBK "Rs 180.00 debited to UBER INDIA on 16-08-26. Ref 778803. -HDFC Bank"

echo "== Shopping =="
send ICICIB "INR 2,499.00 has been debited to MYNTRA on 16-08-26. Ref 778804."

echo "== Bills & Utilities =="
send HDFCBK "Rs 890 debited towards ELECTRICITY BILL. Ref 778805. -HDFC Bank"

echo "== Entertainment =="
send HDFCBK "Rs 649.00 debited to NETFLIX.COM on 16-08-26. Ref 778806. -HDFC Bank"

echo "== Health =="
send HDFCBK "Rs.550.00 debited to APOLLO PHARMACY on 16-08-26. Ref 778807. -HDFC Bank"

echo "== Rent & Housing =="
send HDFCBK "Rs 18000 debited towards HOUSE RENT. Ref 778808. -HDFC Bank"

echo "== Investments =="
send HDFCBK "Rs 5000 debited towards SIP MUTUAL FUND. Ref 778809. -HDFC Bank"

echo "== Transfers (paying a friend, NOT a self-transfer) =="
send HDFCBK "Rs 500 paid to Priya Nair via UPI"

echo "== Salary =="
send HDFCBK "Rs 65000.00 credited from ACME CORP PAYROLL on 16-08-26. Avl Bal Rs 90000"

echo "== Self-transfer: HDFC -> ICICI =="
echo "   (two legs, one shared reference — should collapse into one"
echo "   'Transfer' row and NOT count as expense or income)"
send HDFCBK "Debited Rs 10,000.00 from a/c XX3941 on 16Aug26 14:32 via IMPS to Sakthi ICICI Savings. Ref IMPS998877665544. Avl Bal Rs 40,000.00 -HDFC Bank"
send ICICIB "Rs 10,000.00 credited from Sakthi HDFC Savings on 16-08-26 to a/c XX7788 via IMPS. Ref IMPS998877665544. Avl Bal Rs 55,000.00 -ICICI Bank"

echo "== Needs Review (financial-looking, tier-1 regex can't extract) =="
send ICICIB "Purchase of Rs 599.00 on your ICICI Card XX12 at NETFLIX.COM. Avl Lmt Rs 45000"
send HDFCBK "EMI of Rs 4,500 will be debited to your loan account on 05-08-26"
send HDFCBK "Your SIP installment of Rs 3,000 will be debited on 20-08-26"

echo
echo "Done. Check the Transactions tab for the categorized-looking ones, the"
echo "self-transfer should show as one '⇄ Transfer' row, and the Review tab"
echo "should have 3 pending items."
