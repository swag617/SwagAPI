# Economy Service

`IEconomyService` is a thin, **null-safe** wrapper around [Vault](https://www.spigotmc.org/resources/vault.34315/)'s `Economy` API. It lets dependent plugins call economy methods without ever checking for Vault themselves.

## Obtaining it

```java
RegisteredServiceProvider<IEconomyService> rsp =
        Bukkit.getServicesManager().getRegistration(IEconomyService.class);
IEconomyService economy = rsp.getProvider();
```

## Interface

```java
public interface IEconomyService {
    boolean isEnabled();
    double getBalance(OfflinePlayer player);
    boolean has(OfflinePlayer player, double amount);
    boolean withdraw(OfflinePlayer player, double amount);
    boolean deposit(OfflinePlayer player, double amount);
    String format(double amount);
    String getCurrencyName();
}
```

| Method | Description |
|---|---|
| `isEnabled()` | `true` only if `economy.enabled: true` in config **and** a Vault `Economy` provider is currently registered. Always check this before relying on real balances. |
| `getBalance(player)` | Returns `0.0` when disabled. |
| `has(player, amount)` | Returns `true` when disabled — so gated actions don't accidentally block players on a server with no economy plugin. |
| `withdraw(player, amount)` / `deposit(player, amount)` | Return `true` (success) when disabled, so callers can treat a disabled economy as a no-op success rather than special-casing it. |
| `format(amount)` | Delegates to Vault's currency formatter; falls back to `String.valueOf(amount)` when disabled. |
| `getCurrencyName()` | Vault's plural currency name (e.g. `"Dollars"`); empty string when disabled. |

## Resolution behavior

Economy resolution is **lazy and repeatable**, not just a one-time check at startup. If Vault or an economy plugin loads *after* SwagAPI has already enabled, the next call to any `IEconomyService` method still picks it up — you don't need to restart the server just because load order put Vault after SwagAPI.

## Configuration

```yaml
economy:
  enabled: true
```

Setting this to `false` disables Vault integration entirely, regardless of whether Vault is installed — every method behaves as documented above (reads as `0`, writes report success as a no-op).

## Example

```java
if (economy.isEnabled() && economy.has(player, 100.0)) {
    economy.withdraw(player, 100.0);
    player.sendMessage("Charged " + economy.format(100.0));
} else {
    player.sendMessage("Economy is unavailable — skipping charge.");
}
```

## Related event

`SwagEconomyTransactionEvent` (Bukkit event, `com.SwagDev.SwagAPI.events`) carries `playerUuid`, `amount`, `transactionType`, and `success` fields — intended for plugins that want to react to or log economy transactions across the ecosystem. It is not currently fired automatically by `EconomyService` itself; dependent plugins may fire it when performing their own economy-relevant actions.
