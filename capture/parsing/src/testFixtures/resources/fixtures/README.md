# Fixture corpus

Both files are real notifications posted by Google Wallet on an Android phone (Android 16), captured by the Phase 0 spike against a Desrives access card and a Visa.
Nothing here is written to match the parser.

## Redaction

Merchant names are replaced with structurally equivalent stand-ins that preserve every hazard under test — casing, the double-space and `#` store-number forms, straight `'` and typographic `’` apostrophes, accents.
Card labels are replaced with equivalents that keep their own hazards, and card digits are masked to `0000`.
The same real merchant maps to the same stand-in in both files.

Every timestamp is moved by one constant offset, so no purchase sits on the day or at the hour it was made, while every gap between two notifications — the re-posts, the replay lags — is exactly as captured.
Amounts are exchanged among the transactions of the same file, so each file's total is the real one but no amount stays with its purchase; the amounts a test names, and the purchase exported in both files, keep their own.

Notification keys, device locales, the text formats and the duplicate structure are **untouched**, because those are what the parser is tested against.

## `wallet-week1.jsonl`

21 notifications over 7 days, device locale `en-CA` throughout.

Three noise classes: 9 re-posts, 2 transitional "Preparing your receipt" notifications, 2 empty group summaries.
**8 distinct transactions, 152,00 $.**

## `wallet-week2.jsonl`

24 notifications over 11 days, device locale switched to `fr-CA` partway through — 15 rows `en-CA`, 9 rows `fr-CA`.

**10 distinct transactions, 303,42 $**, with 4 empty group summaries, 2 transitional receipts and 8 re-posts.

What this corpus adds over week one:

- **Real French.** `9,29 $ avec Carte d’accès Coopérative ••0000` — comma decimal, symbol after the amount, `U+00A0` between them.
  Note that this is **NO-BREAK SPACE, not the `U+202F` NARROW NO-BREAK SPACE** that CLDR 42+ specifies; a fixture synthesized from the specification would have guessed wrong.
  `MoneyText` normalises every space variant, so the parser never depended on the guess.
- **Content language does not follow the device.** One row is `$19.99 with ...` on an `fr-CA` device, and another is French text stamped `en-CA` because the listener replayed it 51 hours after posting, by which time the device had been switched back.
  A parser keyed on `deviceLocale` would mis-read both.
- **The deterministic re-post.** Wallet rewrites a payment notification roughly 3 seconds after posting it, with an identical tag. Seven of the ten transactions arrived that way.
- **Replay lag.** Three rows were delivered 22, 22 and 51 hours after they were posted, when the listener rebound.
  This is why capture time is never transaction time.
- **The tagless transitional slot.** Both "Preparing your receipt" rows carry notification id `930510` and a null tag, while payments carry id `1001` and a per-transaction tag.
  One of them sits 52 minutes from any payment.
  Nothing in the payload links a receipt notification to a purchase — and because identity comes from the tag, a tagless notification can never become a ledger row.
