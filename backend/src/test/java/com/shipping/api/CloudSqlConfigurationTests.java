package com.shipping.api;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.profiles.active=cloudsql",
        "DB_NAME=configuration_test",
        "DB_USER=test_user",
        "DB_PASSWORD=test_placeholder",
        "INSTANCE_CONNECTION_NAME=test-project:asia-southeast1:test-instance"
})
class CloudSqlConfigurationTests {
    @Autowired HikariDataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test
    void bindsCloudConnectorSettingsWithoutOpeningANetworkConnection() {
        assertThat(jdbc.getDataSource()).isSameAs(dataSource);
        assertThat(dataSource.getJdbcUrl()).isEqualTo("jdbc:mysql:///configuration_test");
        assertThat(dataSource.getUsername()).isEqualTo("test_user");
        assertThat(dataSource.getMaximumPoolSize()).isEqualTo(5);
        assertThat(dataSource.getMinimumIdle()).isZero();
        assertThat(dataSource.getDataSourceProperties())
                .containsEntry("socketFactory", "com.google.cloud.sql.mysql.SocketFactory")
                .containsEntry("cloudSqlInstance", "test-project:asia-southeast1:test-instance")
                .containsEntry("cloudSqlRefreshStrategy", "lazy")
                .containsEntry("ipTypes", "PUBLIC");
        assertThat(jdbc.getQueryTimeout()).isEqualTo(5);
    }
}
