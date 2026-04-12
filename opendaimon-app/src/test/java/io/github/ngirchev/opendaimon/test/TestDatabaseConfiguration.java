package io.github.ngirchev.opendaimon.test;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Shared Testcontainers PostgreSQL configuration for all integration tests.
 *
 * <p>Uses a single static container for the entire JVM to avoid starting
 * multiple PostgreSQL instances. Each Spring context gets its own database
 * within the shared container to ensure full data isolation.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestDatabaseConfiguration {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.0");

    static {
        POSTGRES.start();
    }

    @Bean
    @ServiceConnection
    public PostgreSQLContainer<?> postgresContainer() {
        String dbName = "testdb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        createDatabase(dbName);
        return new PostgreSQLContainerDelegate(POSTGRES, dbName);
    }

    private static void createDatabase(String dbName) {
        try (Connection conn = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + dbName);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create database: " + dbName, e);
        }
    }

    /**
     * Delegate that returns a custom JDBC URL pointing to a per-context database
     * while reusing the same underlying container.
     */
    static class PostgreSQLContainerDelegate extends PostgreSQLContainer<PostgreSQLContainerDelegate> {

        private final PostgreSQLContainer<?> delegate;
        private final String dbName;

        PostgreSQLContainerDelegate(PostgreSQLContainer<?> delegate, String dbName) {
            super("postgres:17.0");
            this.delegate = delegate;
            this.dbName = dbName;
        }

        @Override
        public void start() {
            // no-op: the real container is already started via the static block
        }

        @Override
        public void stop() {
            // no-op: the real container is managed by Ryuk via the static field
        }

        @Override
        public boolean isRunning() {
            return delegate.isRunning();
        }

        @Override
        public String getJdbcUrl() {
            return delegate.getJdbcUrl().replaceFirst("/test\\b", "/" + dbName);
        }

        @Override
        public String getUsername() {
            return delegate.getUsername();
        }

        @Override
        public String getPassword() {
            return delegate.getPassword();
        }

        @Override
        public String getHost() {
            return delegate.getHost();
        }

        @Override
        public Integer getMappedPort(int originalPort) {
            return delegate.getMappedPort(originalPort);
        }

        @Override
        public Integer getFirstMappedPort() {
            return delegate.getFirstMappedPort();
        }

        @Override
        public String getDatabaseName() {
            return dbName;
        }
    }
}
