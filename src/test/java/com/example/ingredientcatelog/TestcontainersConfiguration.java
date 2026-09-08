package com.example.ingredientcatelog;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    // One datasource container. Two @ServiceConnection JDBC containers make the
    // datasource ambiguous and the context fails to start.
    // Pinned to 8.4 to match RecipeCatelog / User / Projects/compose.yaml (and so
    // an image bump to MySQL 9.x can't silently break the build).
    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return new MySQLContainer(DockerImageName.parse("mysql:8.4"));
    }

}
