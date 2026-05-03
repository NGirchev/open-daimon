package io.github.ngirchev.opendaimon.test;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.HostPortWaitStrategy;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

/**
 * Abstract base class for all integration tests that need infrastructure containers.
 *
 * <p>Provides singleton PostgreSQL and MinIO containers shared across the entire JVM.
 * Each Spring context gets its own database within PostgreSQL (via UUID suffix)
 * to ensure full data isolation between test classes with different configurations.
 *
 * <p>Ryuk automatically stops all containers when the JVM exits.
 *
 * @see <a href="https://java.testcontainers.org/test_framework_integration/manual_lifecycle_control/">
 *     Testcontainers Singleton Pattern</a>
 */
public abstract class AbstractContainerIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.0");

    @SuppressWarnings("resource")
    static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:latest")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .withCommand("server", "/data")
            .waitingFor(new HttpWaitStrategy()
                    .forPath("/minio/health/ready")
                    .forPort(9000)
                    .withStartupTimeout(Duration.ofSeconds(30)));

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .waitingFor(new HostPortWaitStrategy()
                    .withStartupTimeout(Duration.ofSeconds(30)));

    static {
        POSTGRES.start();
        MINIO.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        String dbName = "testdb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        createDatabase(dbName);
        String jdbcUrl = POSTGRES.getJdbcUrl().replaceFirst("/test\\b", "/" + dbName);
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");

        String minioEndpoint = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        registry.add("open-daimon.common.storage.minio.endpoint", () -> minioEndpoint);
        registry.add("open-daimon.common.storage.minio.access-key", () -> "minioadmin");
        registry.add("open-daimon.common.storage.minio.secret-key", () -> "minioadmin");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
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
}
