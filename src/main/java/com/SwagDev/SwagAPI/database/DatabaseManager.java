package com.SwagDev.SwagAPI.database;

import com.SwagDev.SwagAPI.SwagAPI;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;

public final class DatabaseManager {

    private DatabaseManager() {}

    public static HikariDataSource buildPool(SwagAPI plugin) {
        FileConfiguration config = plugin.getConfig();
        String type = config.getString("database.type", "sqlite").toLowerCase();

        HikariConfig hikariConfig;
        if (type.equals("mysql")) {
            hikariConfig = MySQLDatabase.buildConfig(config);
        } else {
            String fileName = config.getString("database.sqlite.file", "swagapi.db");
            File dbFile = new File(plugin.getDataFolder(), fileName);
            hikariConfig = SQLiteDatabase.buildConfig(dbFile);
        }

        return new HikariDataSource(hikariConfig);
    }
}
