package com.example.cachestampede.infrastructure.cache

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "cache.stampede")
data class CacheProperties(
    val baseTtlSeconds: Long = 60,
    val jitterMaxSeconds: Long = 10,
    val softTtlRatio: Double = 0.8,
    val lockTimeoutSeconds: Long = 5,
    val lockRetryIntervalMs: Long = 50,
    val lockMaxRetries: Int = 100
)
