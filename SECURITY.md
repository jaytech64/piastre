# Security

Piastre has no network access, so the ways it can fail its users are narrow but serious: a notification read that should not have been, data kept longer than it says, a backup or export path that leaks the ledger, or a build that quietly gains a network permission.

## Reporting a problem

Report it privately through GitHub's [private vulnerability reporting](https://github.com/jaytech64/piastre/security/advisories/new), not in a public issue.
Say which build (Play or F-Droid), which version, and how to reproduce it.

Never include real card digits, real merchants or an export of a real ledger: describe the shape of the data instead.

## Supported versions

Only the latest release is fixed.
