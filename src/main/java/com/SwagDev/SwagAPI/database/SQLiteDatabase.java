package com.SwagDev.SwagAPI.database;

import com.zaxxer.hikari.HikariConfig;

import java.io.File;

public final class SQLiteDatabase {

    private SQLiteDatabase() {}

    public static HikariConfig buildConfig(File dbFile) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        config.setMaximumPoolSize(1);
        config.setConnectionTestQuery("SELECT 1");
        config.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;");
        config.setPoolName("SwagAPI-SQLite");
        return config;
    }
}