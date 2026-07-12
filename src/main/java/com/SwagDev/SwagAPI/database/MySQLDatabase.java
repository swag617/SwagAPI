package com.SwagDev.SwagAPI.database;

import com.zaxxer.hikari.HikariConfig;
import org.bukkit.configuration.file.FileConfiguration;

public final class MySQLDatabase {

    private MySQLDatabase() {}

    public static HikariConfig buildConfig(FileConfiguration config) {
        String host     = config.getString("database.mysql.host", "localhost");
        int    port     = config.getInt("database.mysql.port", 3306);
        String database = config.getString("database.mysql.database", "swagapi");
        String username = config.getString("database.mysql.username", "root");
        String password = config.getString("database.mysql.password", "");
        int    poolSize = config.getInt("database.mysql.pool-size", 10);
        long   connTimeout  = config.getLong("database.mysql.connection-timeout", 30000);
        long   idleTimeout  = config.getLong("database.mysql.idle-timeout", 600000);
        long   maxLifetime  = config.getLong("database.mysql.max-lifetime", 1800000);
        boolean useSSL      = config.getBoolean("database.mysql.use-ssl", false);

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + useSSL
                + "&allowPublicKeyRetrieval=true"
                + "&serverTimezone=UTC";

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikariConfig.setJdbcUrl(url);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setMaximumPoolSize(poolSize);
        hikariConfig.setConnectionTimeout(connTimeout);
        hikariConfig.setIdleTimeout(idleTimeout);
        hikariConfig.setMaxLifetime(maxLifetime);
        hikariConfig.setPoolName("SwagAPI-MySQL");
        return hikariConfig;
    }
}
