package dev.soup.queue.job;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One real Postgres 16 for all repository tests (singleton container), migrated once with the
 * production migrations. Uses the same connection settings as the service.
 */
final class TestDb {

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16");
    static final DataSource DATA_SOURCE;
    static final JdbcTemplate JDBC;
    static final JobRepository REPO;

    static {
        PG.start();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        cfg.setMaximumPoolSize(40);
        cfg.setConnectionInitSql("SET idle_in_transaction_session_timeout = '2s'");
        DATA_SOURCE = new HikariDataSource(cfg);
        Flyway.configure().dataSource(DATA_SOURCE).load().migrate();
        JDBC = new JdbcTemplate(DATA_SOURCE);
        REPO = new JobRepository(JDBC, new TransactionTemplate(new DataSourceTransactionManager(DATA_SOURCE)));
    }

    private TestDb() {
    }

    static void clean() {
        JDBC.execute("TRUNCATE job_transitions, effects, executions, jobs");
    }

    static Job newJob(String key) {
        return REPO.insertIfAbsent(java.util.UUID.randomUUID(), key, "hash-" + key, "{\"k\":\"" + key + "\"}")
                .orElseThrow();
    }

    static int count(String sql, Object... args) {
        Integer n = JDBC.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
