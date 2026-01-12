package com.example.cachestampede.infrastructure.cache.strategy

import com.example.cachestampede.infrastructure.cache.CacheProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration

/**
 * TTL Jitter 전략
 * - 캐시 TTL에 랜덤 값을 추가하여 만료 시점 분산
 * - 동일 시점에 캐시가 만료되는 것을 방지
 */
class JitterCacheStrategy(
    redisTemplate: RedisTemplate<String, Any>,
    cacheProperties: CacheProperties,
    cacheObjectMapper: ObjectMapper
) : BaseCacheStrategy(redisTemplate, cacheProperties, cacheObjectMapper) {

    override val strategyName: String = "jitter"

    override fun getTtl(): Duration = getJitteredTtl()
}
