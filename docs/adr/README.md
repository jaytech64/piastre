# Architecture decision records

One file per decision that would be expensive to reverse, written when it was made rather than reconstructed afterwards.

A record here answers one question: *why is the code like this, and what did we give up?*
It is not a design document and not a specification.
If a decision can be read off the code in thirty seconds, it does not need a record.

## The records

| # | Decision | Status |
|---|---|---|
| [0001](0001-raw-log-90-day-purge.md) | Raw log: 90-day purge, unparsed rows kept | Accepted — implemented |
| [0002](0002-totals-exclude-unreviewed-rows.md) | Totals exclude unreviewed rows | Accepted — implemented |
| [0003](0003-export-writes-csv-and-json.md) | Export writes both CSV and JSON | Accepted — implemented |
| [0004](0004-capture-health-banner-without-a-notification.md) | A banner for broken capture, and no notification | Accepted, revised twice — implemented |
| [0005](0005-import-merges-and-never-replaces.md) | Import merges into the ledger and never replaces it | Accepted — implemented |
| [0006](0006-a-filtered-total-answers-for-one-account.md) | A filtered total answers for one account | Accepted — amended by 0017 |
| [0007](0007-a-report-counts-spending-not-the-balance.md) | A report counts spending, not the balance | Accepted, revised once — amended by 0009 and 0017 |
| [0008](0008-the-ledger-header-says-what-the-balance-is-made-of.md) | The ledger header says what the balance is made of | Accepted — amended by 0017 |
| [0009](0009-a-report-says-what-its-balance-is-made-of.md) | A report says what its balance is made of | Accepted — amended by 0017 |
| [0010](0010-a-merchants-category-is-written-onto-each-purchase.md) | A merchant's category is written onto each purchase, not read through | Accepted — implemented |
| [0011](0011-a-transfer-under-a-category-is-money-that-left.md) | A transfer under a category is money that left for it | Superseded by 0017 |
| [0012](0012-a-total-counts-one-currency.md) | A total counts one currency | Accepted — implemented |
| [0013](0013-a-recurring-transaction-is-written-on-its-day.md) | A recurring transaction is written on its day, never projected | Accepted, revised three times — implemented |
| [0014](0014-a-dated-filters-headline-is-the-balance-of-its-dates.md) | A dated filter's headline is the balance of its dates | Accepted, revised once — implemented |
| [0015](0015-a-transaction-counts-from-its-day.md) | A transaction counts from its day | Accepted — implemented |
| [0016](0016-a-categorys-group-is-read-through.md) | A category's group is read through, never written onto the row | Accepted, revised once — implemented |
| [0017](0017-a-transfer-counts-by-the-accounts-it-joins.md) | A transfer counts by the accounts it joins | Accepted — amended by 0019 |
| [0018](0018-a-split-divides-one-transaction-among-categories.md) | A split divides one transaction among categories | Accepted — amended by 0019 |
| [0019](0019-a-part-of-a-split-can-go-into-another-account.md) | A part of a split can go into another account | Accepted — implemented |
| [0020](0020-a-categorys-icon-is-stored-by-name-and-read-through.md) | A category's icon is stored by name and read through | Accepted — implemented |

0001 through 0004 are the calls that were the product owner's to make during Phase 2, backfilled from the phase plan.
Each record after them was written with the branch that made the decision.
The engineering decisions that plan also records — parsing inline with a catch-up pass, the raw log's uniqueness rule, the `extrasJson` allowlist — are documented in the KDoc of the classes that implement them, where they are impossible to read the code without seeing.

## Writing a new one

Copy the shape of an existing record: **Status**, **Decided**, **Context**, **Decision**, **Consequences**, **Where this lives in the code**.
Number it sequentially and add a row above.

Never edit the Decision section of an accepted record to match a change of mind.
Either add a **Revision** section saying what changed and why — as [0004](0004-capture-health-banner-without-a-notification.md) does — or write a new record that supersedes it.
A record whose history has been tidied away is worth nothing, because the reason it exists is that someone will one day want to reopen the question.
