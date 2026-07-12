package com.SwagDev.SwagAPI.api;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

public interface IDatabaseService {

    Connection getConnection() throws SQLException;

    HikariDataSource getDataSource();

    boolean isMySQL();

    boolean isSQLite();

    void executeAsync(Runnable task);

    <T> CompletableFuture<T> queryAsync(Callable<T> query);
}
