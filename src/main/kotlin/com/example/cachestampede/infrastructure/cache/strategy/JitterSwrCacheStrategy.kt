package com.example.cachestampede.infrastructure.cache.strategy

import com.example.cachestampede.infrastructure.cache.CacheProperties
import com.example.cachestampede.infrastructure.cache.CachedValue
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * TTL Jitter + Stale-While-Revalidate 전략
 * - Soft TTL: 이 시간이 지나면 백그라운드에서 갱신 시작
 * - Hard TTL: 이 시간이 지나면 반드시 동기 갱신 필요
 * - Soft~Hard TTL 사이에서는 stale 데이터를 반환하면서 비동기 갱신
 */
class JitterSwrCacheStrategy(
    redisTemplate: RedisTemplate<String, Any>,
    cacheProperties: CacheProperties,
    cacheObjectMapper: ObjectMapper
) : BaseCacheStrategy(redisTemplate, cacheProperties, cacheObjectMapper) {

    override val strategyName: String = "jitter-swr"

    // 동시에 여러 갱신 요청이 발생하는 것을 방지하기 위한 마커
    private val refreshingKeys = ConcurrentHashMap.newKeySet<String>()

    override fun <T : Any> getOrLoad(key: String, type: Class<T>, loader: () -> T?): T? {
        val cacheKey = buildCacheKey(key)

        // 1. 캐시 조회 (SWR 래퍼 포함)
        val cachedValue = getCachedValueFromCache(cacheKey, type)

        if (cachedValue != null) {
            when {
                cachedValue.isFresh() -> {
                    log.debug("[{}] Cache HIT (fresh): key={}", strategyName, cacheKey)
                    return cachedValue.value
                }

                cachedValue.isStale() -> {
                    log.debug("[{}] Cache HIT (stale): key={}, triggering background refresh", strategyName, cacheKey)
                    // 백그라운드에서 갱신 트리거
                    triggerBackgroundRefresh(cacheKey, type, loader)
                    // stale 데이터 즉시 반환
                    return cachedValue.value
                }

                // Hard TTL 이후 - 캐시 만료됨
                else -> {
                    log.debug("[{}] Cache EXPIRED: key={}", strategyName, cacheKey)
                }
            }
        } else {
            log.debug("[{}] Cache MISS: key={}", strategyName, cacheKey)
        }

        // 2. 캐시 미스 또는 Hard TTL 만료 - 동기 갱신
        return loadAndCache(cacheKey, loader)
    }

    private fun <T : Any> triggerBackgroundRefresh(
        cacheKey: String,
        type: Class<T>,
        loader: () -> T?
    ) {
        // 이미 갱신 중인 키는 스킵
        if (!refreshingKeys.add(cacheKey)) {
            log.debug("[{}] Already refreshing: key={}", strategyName, cacheKey)
            return
        }

        CompletableFuture.runAsync {
            try {
                log.debug("[{}] Background refresh started: key={}", strategyName, cacheKey)
                loadAndCache(cacheKey, loader)
                log.debug("[{}] Background refresh completed: key={}", strategyName, cacheKey)
            } catch (e: Exception) {
                log.error("[{}] Background refresh failed: key={}, error={}", strategyName, cacheKey, e.message)
            } finally {
                refreshingKeys.remove(cacheKey)
            }
        }
    }

    private fun <T : Any> loadAndCache(cacheKey: String, loader: () -> T?): T? {
        val value = loader() ?: return null

        // SWR 래퍼로 저장
        val jitter = Random.nextLong(0, cacheProperties.jitterMaxSeconds + 1)
        val cachedValue = CachedValue.create(
            value = value,
            baseTtlSeconds = cacheProperties.baseTtlSeconds,
            softTtlRatio = cacheProperties.softTtlRatio,
            jitterSeconds = jitter
        )

        // Hard TTL 기준으로 Redis TTL 설정
        val redisTtl = Duration.ofSeconds(cacheProperties.baseTtlSeconds + jitter)
        saveToCache(cacheKey, cachedValue, redisTtl)
        log.debug("[{}] Cache SET: key={}, ttl={}s", strategyName, cacheKey, redisTtl.seconds)

        return value
    }

    override fun invalidate(key: String) {
        val cacheKey = buildCacheKey(key)
        redisTemplate.delete(cacheKey)
        refreshingKeys.remove(cacheKey)
        log.debug("[{}] Cache INVALIDATE: key={}", strategyName, cacheKey)
    }
}
