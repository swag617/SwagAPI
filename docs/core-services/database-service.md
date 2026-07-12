# Database Service

`IDatabaseService` gives every dependent plugin access to one shared **HikariCP** connection pool, instead of each plugin opening its own SQLite file or MySQL connection.

## Obtaining it

```java
RegisteredServiceProvider<IDatabaseService> rsp =
        Bukkit.getServicesManager().getRegistration(IDatabaseService.class);
IDatabaseService db = rsp.getProvider();
```

## Interface

```java
public interface IDatabaseService {
    Connection getConnection() throws SQLException;
    HikariDataSource getDataSource();
    boolean isMySQL();
    boolean isSQLite();
    void executeAsync(Runnable task);
    <T> CompletableFuture<T> queryAsync(Callable<T> query);
}
```

| Method | Description |
|---|---|
| `getConnection()` | Borrows a JDBC `Connection` from the shared HikariCP pool. Always use try-with-resources to return it. |
| `getDataSource()` | Returns the raw `HikariDataSource`, e.g. for reading pool metrics (`getHikariPoolMXBean()`, as `/swagapi status` does). |
| `isMySQL()` / `isSQLite()` | Branch your SQL syntax on the active backend — e.g. `INSERT OR REPLACE` (SQLite) vs. `ON DUPLICATE KEY UPDATE` (MySQL) for upserts, or `INTEGER PRIMARY KEY AUTOINCREMENT` vs. `INT AUTO_INCREMENT`. |
| `executeAsync(Runnable)` | Runs a task on a Bukkit async scheduler thread — use for fire-and-forget writes. |
| `queryAsync(Callable<T>)` | Runs a query off the main thread and returns a `CompletableFuture<T>` with the result (or the exception, via `completeExceptionally`). |

## Configuration

```yaml
database:
  type: sqlite            # or "mysql"
  sqlite:
    file: swagapi.db
  mysql:
    host: localhost
    port: 3306
    database: swagapi
    username: root
    password: ""
    pool-size: 10
    connection-timeout: 30000
    idle-timeout: 600000
    max-lifetime: 1800000
    use-ssl: false
```

Changing `database.type` requires a full server restart — `/swagapi reload` reloads config values but does not rebuild the connection pool.

## Example: an upsert that works on both backends

This is the exact pattern SwagAPI's own `PlayerDataService` uses internally:

```java
String sql = db.isMySQL()
        ? "INSERT INTO my_table (uuid, value) VALUES (?,?) ON DUPLICATE KEY UPDATE value=VALUES(value)"
        : "INSERT OR REPLACE INTO my_table (uuid, value) VALUES (?,?)";

try (Connection conn = db.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
    ps.setString(1, uuid.toString());
    ps.setString(2, value);
    ps.executeUpdate();
}
```

## Tables SwagAPI creates for itself

SwagAPI creates two tables of its own on startup (`CREATE TABLE IF NOT EXISTS`) to back the Player Data Service:

| Table | Columns | Purpose |
|---|---|---|
| `swagapi_players` | `uuid` (PK), `username`, `first_join`, `last_seen` | One row per known player |
| `swagapi_modules` | `uuid`, `plugin_key`, `json_data` (PK: `uuid`+`plugin_key`) | Reserved for future generic module storage |

> Dependent plugins are expected to create and manage their own tables through the shared connection — SwagAPI does not namespace or restrict table names.

## Thread safety

Never call `getConnection()` or run blocking queries on the main server thread. Always wrap database work in `executeAsync`/`queryAsync`, or run it from your own async scheduler task, then hop back to the main thread (`Bukkit.getScheduler().runTask(...)`) before touching any Bukkit API with the result.
