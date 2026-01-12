package com.example.cachestampede.infrastructure.cache.strategy

import com.example.cachestampede.infrastructure.cache.CacheProperties
import com.example.cachestampede.infrastructure.cache.lock.DistributedLock
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.RedisTemplate

@Configuration
class CacheStrategyConfig {

    @Bean
    fun basicCacheStrategy(
        redisTemplate: RedisTemplate<String, Any>,
        cacheProperties: CacheProperties,
        cacheObjectMapper: ObjectMapper
    ): BaseCacheStrategy = BaseCacheStrategy(redisTemplate, cacheProperties, cacheObjectMapper)

    @Bean
    fun jitterCacheStrategy(
        redisTemplate: RedisTemplate<String, Any>,
        cacheProperties: CacheProperties,
        cacheObjectMapper: ObjectMapper
    ): JitterCacheStrategy = JitterCacheStrategy(redisTemplate, cacheProperties, cacheObjectMapper)

    @Bean
    fun jitterSwrCacheStrategy(
        redisTemplate: RedisTemplate<String, Any>,
        cacheProperties: CacheProperties,
        cacheObjectMapper: ObjectMapper
    ): JitterSwrCacheStrategy = JitterSwrCacheStrategy(redisTemplate, cacheProperties, cacheObjectMapper)

    @Bean
    fun jitterLockCacheStrategy(
        redisTemplate: RedisTemplate<String, Any>,
        cacheProperties: CacheProperties,
        cacheObjectMapper: ObjectMapper,
        distributedLock: DistributedLock
    ): JitterLockCacheStrategy = JitterLockCacheStrategy(redisTemplate, cacheProperties, cacheObjectMapper, distributedLock)

    @Bean
    fun fullProtectionCacheStrategy(
        redisTemplate: RedisTemplate<String, Any>,
        cacheProperties: CacheProperties,
        cacheObjectMapper: ObjectMapper,
        distributedLock: DistributedLock
    ): FullProtectionCacheStrategy = FullProtectionCacheStrategy(redisTemplate, cacheProperties, cacheObjectMapper, distributedLock)
}
