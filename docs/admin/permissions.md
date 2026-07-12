# Permissions

SwagAPI declares three permission nodes in `plugin.yml`, all defaulting to **op**:

| Permission | Default | Description |
|---|---|---|
| `swagapi.admin` | op | Access to every `/swagapi` subcommand — `reload`, `status`, `info`, `updatecheck`, `update`, `updates`, and `web`. This is the only permission actually checked by `SwagAPICommand`; it gates the entire command tree. |
| `swagapi.web` | op | Controls whether a player receives the automatic "no web panel account found" join reminder (`WebAccountReminderListener`), when `web-server.auth.remind-ops-on-join` is enabled. |
| `swagapi.updates` | op | Declared for staging plugin updates via SwagAPI. |

## What's actually enforced in code

Reading the current implementation:

* **`swagapi.admin`** gates `/swagapi` entirely (all subcommands, including `update`/`updates`/`web`) and also gates who receives update-available notifications on join (`AdminJoinUpdateListener`) and in the periodic manifest-check broadcast (`UpdateService#notifyAdmins`).
* **`swagapi.web`** is checked in exactly one place: whether a player gets the automatic account-setup reminder on join. It is **not** currently required to run `/swagapi web ...` — that subcommand tree is already covered by `swagapi.admin`.
* **`swagapi.updates`** is declared in `plugin.yml` but is not currently checked anywhere in the codebase. Staging updates today is gated purely by `swagapi.admin` via the shared `/swagapi` permission check. Treat this node as reserved for a future finer-grained permission split rather than something you can grant standalone today.

## Practical implication

Because every subcommand shares the single `swagapi.admin` gate, there is currently no way to grant a staff member access to (for example) `/swagapi web whoami` without also granting them `/swagapi update` and `/swagapi reload`. If you need finer-grained staff permissions, restrict access via your permissions plugin at the command-alias level instead, or wait for `swagapi.updates`/`swagapi.web` to be wired into the command handler in a future version.
