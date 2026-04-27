package com.vistora.discovery.config;

import com.vistora.discovery.context.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Hibernate configuration for dynamic tenant schema support.
 * This uses a DataSource wrapper that executes "USE <schema>" on each connection.
 */
@Configuration
public class HibernateConfig {

    private static final Logger log = LoggerFactory.getLogger(HibernateConfig.class);

    /**
     * Creates the actual HikariCP DataSource with connection pooling.
     * This reads ALL configuration from spring.datasource properties including:
     * - url, username, password
     * - hikari.* properties (pool-name, maximum-pool-size, etc.)
     */
    @Bean(name = "actualDataSource")
    @ConfigurationProperties(prefix = "spring.datasource")
    public HikariDataSource actualDataSource() {
        return new HikariDataSource();
    }

    /**
     * Wraps the actual DataSource to set schema dynamically per connection.
     * This is the primary DataSource that will be injected throughout the application.
     */
    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("actualDataSource") DataSource actualDataSource) {
        return new TenantAwareDataSource(actualDataSource);
    }

    /**
     * DataSource wrapper that sets the MySQL database schema dynamically
     * based on TenantContext before returning a connection.
     */
    static class TenantAwareDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {

        private static final Logger log = LoggerFactory.getLogger(TenantAwareDataSource.class);

        public TenantAwareDataSource(DataSource targetDataSource) {
            super(targetDataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = super.getConnection();
            setSchemaIfNeeded(connection);
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection connection = super.getConnection(username, password);
            setSchemaIfNeeded(connection);
            return connection;
        }

        private void setSchemaIfNeeded(Connection connection) {
            String tenantName = TenantContext.getTenantName();

            if (tenantName != null && !tenantName.isEmpty()) {
                try {
                    // Execute USE <schema> to set the default database for this connection
                    connection.createStatement().execute("USE `" + tenantName + "`");
                    log.debug("Set connection schema to: {}", tenantName);
                } catch (SQLException e) {
                    log.warn("Failed to set schema to '{}': {}", tenantName, e.getMessage());
                    // Don't throw - let the query fail naturally if schema doesn't exist
                }
            } else {
                log.trace("No tenant context - shared tables will use default schema from JDBC URL");
            }
        }
    }
}
