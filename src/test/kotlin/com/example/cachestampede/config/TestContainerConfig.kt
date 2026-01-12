package com.example.cachestampede.config

import com.redis.testcontainers.RedisContainer
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer

@TestConfiguration
class TestContainerConfig {

    companion object {
        @JvmStatic
        @Bean
        @ServiceConnection
        fun postgresContainer(): PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("cache_stampede_test")
                .withUsername("test")
                .withPassword("test")

        @JvmStatic
        @Bean
        @ServiceConnection
        fun redisContainer(): RedisContainer =
            RedisContainer("redis:7-alpine")
    }
}
