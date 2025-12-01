package ru.study.persistence.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

public final class EntityManagerFactoryProvider {
    private static final Logger log = LoggerFactory.getLogger(EntityManagerFactoryProvider.class);
    private static volatile EntityManagerFactory emf;
    private static boolean migrationsRun = false;

    private EntityManagerFactoryProvider() {}

    public static synchronized EntityManagerFactory getEntityManagerFactory() {
        if (emf == null) {
            log.info("Creating EntityManagerFactory...");
            
            // Получаем конфигурацию БД
            String dbUrl = determineDatabaseUrl();
            log.info("Using database URL: {}", dbUrl);

            // Запускаем миграции только один раз
            if (!migrationsRun) {
                runLiquibaseMigrations(dbUrl);
                migrationsRun = true;
            }

            // Создаем EntityManagerFactory с правильным URL
            Map<String, Object> properties = new HashMap<>();
            properties.put("jakarta.persistence.jdbc.driver", "org.h2.Driver");
            properties.put("jakarta.persistence.jdbc.url", dbUrl);
            properties.put("jakarta.persistence.jdbc.user", "sa");
            properties.put("jakarta.persistence.jdbc.password", "");
            properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
            properties.put("hibernate.hbm2ddl.auto", "validate"); // или "none"
            properties.put("hibernate.show_sql", "true");
            properties.put("hibernate.format_sql", "true");
            
            emf = Persistence.createEntityManagerFactory("mailPU", properties);
            log.info("EntityManagerFactory created successfully");
        }
        return emf;
    }

    public static EntityManager createEntityManager() {
        return getEntityManagerFactory().createEntityManager();
    }

    public static synchronized void close() {
        if (emf != null && emf.isOpen()) {
            emf.close();
            emf = null;
        }
    }

    private static String determineDatabaseUrl() {
        return "jdbc:h2:file:./../data/maildb;AUTO_SERVER=TRUE";
    }

    private static void runLiquibaseMigrations(String jdbcUrl) {
        log.info("Running Liquibase migrations for database: {}", jdbcUrl);
        
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            
            Liquibase liquibase = new Liquibase(
                "db/changelog/db.changelog-master.yaml",
                new ClassLoaderResourceAccessor(),
                database
            );
            
            liquibase.update();
            log.info("Liquibase migrations completed successfully");
        } catch (Exception e) {
            log.error("Liquibase migration failed", e);
            throw new RuntimeException("Failed to run database migrations", e);
        }
    }
}