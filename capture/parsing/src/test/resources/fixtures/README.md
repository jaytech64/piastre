# Fixture corpus

## `wallet-en-CA.jsonl` — real, redacted

21 notifications captured by the Phase 0 spike over 7 days on an Android phone (Android 16,
device locale `en-CA`), against a Desrives access card and a Visa.

**Redacted before entering the repository.** Merchant names are replaced with
structurally equivalent stand-ins that preserve the hazards under test — casing,
double-space and `#` store-number forms, apostrophes, accents — and card digits are
masked to `0000`. Amounts, timing, notification keys and the duplicate structure are
untouched, because those are what the parser is tested against.

Contains all three noise classes: 9 re-posts, 2 transitional "Preparing your receipt"
notifications, 2 empty group summaries. 8 distinct transactions, 152,00 $.

## `wallet-fr-CA.jsonl` — synthesized, NOT captured

Derived from the en-CA rows using the French format the project owner captured
previously with a Tasker automation:

    7,45 $ avec Remise Visa ••0000
    7,45 $ avec Carte d'accès Desrives ••0000

The space characters are the one detail his report could not carry, so this file uses
U+202F (narrow no-break space), which is what CLDR 42+ specifies for French currency.
`MoneyTextTest` covers U+00A0, U+2009, U+0020 and no-space independently, so the parser
does not depend on that guess being right.

The two transitional notifications are **absent** rather than translated: their French
wording is unknown and inventing it would make the corpus lie.

**Replace this file with real captures** once the spike has run with the device in
français, and drop the `version` of the parser by one to force a re-parse.
