# Login & Accounts

SwagAPI owns the **only** login system in the Swag617 ecosystem — every registered plugin module sits behind the same session, so there is one account system to manage, not one per plugin.

## Creating your first account

There's no default admin account. Since `/setup` requires a valid one-time token, the only way to mint the first one is in-game:

```
/swagapi web setup
```

This requires `swagapi.admin` (it's a subcommand of `/swagapi`) and sends the command's sender a clickable chat link that opens `/setup?token=...` in their browser. The token is bound to the sender's player `UUID` and expires after `web-server.auth.setup-token-minutes` (default 15 minutes).

On the setup page, choose a username (3–16 characters, letters/numbers/underscore) and a password (minimum 8 characters). The new account is automatically linked to the player who requested the link.

> If `web-server.auth.remind-ops-on-join` is enabled (default), any OP with the `swagapi.web` permission and no linked account gets this same setup link automatically, once per join, without needing to run the command.

## Logging in

`/login` presents a username/password form. On success, a session cookie (`swag_session`) is set and the browser is redirected either to `/home` or to whatever `?redirect=` path originally bounced the user to the login page.

If no accounts exist yet, the login page shows a hint pointing the visitor at `/swagapi web setup`.

## Forgot your password?

`/forgot-password` accepts a username and, regardless of whether that username exists (to avoid leaking which accounts are real), shows the same "check in-game chat" confirmation. If the account exists and has a linked player:

* **Online** — the reset link is delivered immediately via a clickable in-game chat message.
* **Offline** — the reset request is queued and delivered automatically the next time that player joins (`WebAccountReminderListener` checks for a pending reset on every join).

An admin can also trigger this on someone else's behalf with `/swagapi web resetpassword <username>`, or from the `/account` page's account table (see below).

## `/account` — your own account

Once logged in, `/account`:

* Shows who you're currently signed in as
* Lets you change your own password (requires re-entering your current one)
* Lists **every** panel account in a table — username, linked player, creation date — each with a "Send Reset Link" button that triggers the same offline-queued/online-delivered flow as `/swagapi web resetpassword`

## Command reference

| Command | Effect |
|---|---|
| `/swagapi web setup` | Get a fresh account-creation link for yourself. |
| `/swagapi web whoami [player]` | Show which panel account(s), if any, are linked to a player (yourself if omitted). |
| `/swagapi web resetpassword <username>` | Send that account a password reset link. |

All require `swagapi.admin` (see [Permissions](../admin/permissions.md)).

## Security notes

* Passwords are hashed with PBKDF2WithHmacSHA256, 65,536 iterations, unique 16-byte salt per account — never stored or logged in plaintext.
* Setup and reset tokens are single-use, 32-byte random hex, held only in memory (not persisted), and expire after `setup-token-minutes`.
* Session cookies are `HttpOnly` and `SameSite=Lax`, so they aren't readable by page JavaScript and aren't sent on cross-site requests.
* Setting `web-server.auth.enabled: false` removes the login requirement from **every** route on the server, including every dependent plugin's registered module — only do this on a port that isn't reachable from outside your network.
