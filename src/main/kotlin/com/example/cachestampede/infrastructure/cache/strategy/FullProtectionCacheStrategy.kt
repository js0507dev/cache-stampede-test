package com.example.cachestampede.infrastructure.cache.strategy

import com.example.cachestampede.infrastructure.cache.CacheProperties
import com.example.cachestampede.infrastructure.cache.CachedValue
import com.example.cachestampede.infrastructure.cache.lock.DistributedLock
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 전체 보호 전략: TTL Jitter + SWR + Distributed Lock
 * - 가장 강력한 스탬피드 방지 전략
 * - Soft TTL 이후: 백그라운드 갱신 + 락으로 동시 갱신 방지
 * - Hard TTL 이후: 동기 갱신 + 락으로 동시 갱신 방지
 */
class FullProtectionCacheStrategy(
    redisTemplate: RedisTemplate<String, Any>,
    cacheProperties: CacheProperties,
    cacheObjectMapper: ObjectMapper,
    private val distributedLock: DistributedLock
) : BaseCacheStrategy(redisTemplate, cacheProperties, cacheObjectMapper) {

    override val strategyName: String = "full-protection"

    private val refreshingKeys = ConcurrentHashMap.newKeySet<String>()

    override fun <T : Any> getOrLoad(key: String, type: Class<T>, loader: () -> T?): T? {
        val cacheKey = buildCacheKey(key)

        // 1. 캐시 조회
        val cachedValue = getCachedValueFromCache(cacheKey, type)

        if (cachedValue != null) {
            when {
                cachedValue.isFresh() -> {
                    log.debug("[{}] Cache HIT (fresh): key={}", strategyName, cacheKey)
                    return cachedValue.value
                }

                cachedValue.isStale() -> {
                    log.debug("[{}] Cache HIT (stale): key={}, triggering background refresh with lock", strategyName, cacheKey)
                    triggerBackgroundRefreshWithLock(key, cacheKey, type, loader)
                    return cachedValue.value
                }

                else -> {
                    log.debug("[{}] Cache EXPIRED: key={}", strategyName, cacheKey)
                }
            }
        } else {
            log.debug("[{}] Cache MISS: key={}", strategyName, cacheKey)
        }

        // 2. 동기 갱신 (락 사용)
        return loadWithLock(key, cacheKey, type, loader)
    }

    private fun <T : Any> triggerBackgroundRefreshWithLock(
        key: String,
        cacheKey: String,
        type: Class<T>,
        loader: () -> T?
    ) {
        if (!refreshingKeys.add(cacheKey)) {
            log.debug("[{}] Already refreshing: key={}", strategyName, cacheKey)
            return
        }

        CompletableFuture.runAsync {
            // 전략별로 락 네임스페이스 분리 (전략 간 간섭 방지)
            val lockKey = "refresh:${strategyName}:$key"
            val lockTimeout = Duration.ofSeconds(cacheProperties.lockTimeoutSeconds)

            if (distributedLock.tryLock(lockKey, lockTimeout)) {
                try {
                    log.debug("[{}] Background refresh started with lock: key={}", strategyName, cacheKey)

                    // 락 획득 후 다시 캐시 상태 확인
                    val currentValue = getCachedValueFromCache(cacheKey, type)

                    // 이미 fresh하면 갱신 스킵
                    if (currentValue?.isFresh() == true) {
                        log.debug("[{}] Cache already refreshed by another process: key={}", strategyName, cacheKey)
                        return@runAsync
                    }

                    loadAndCache(cacheKey, loader)
                    log.debug("[{}] Background refresh completed: key={}", strategyName, cacheKey)
                } catch (e: Exception) {
                    log.error("[{}] Background refresh failed: key={}, error={}", strategyName, cacheKey, e.message)
                } finally {
                    distributedLock.unlock(lockKey)
                    refreshingKeys.remove(cacheKey)
                }
            } else {
                log.debug("[{}] Could not acquire lock for background refresh: key={}", strategyName, cacheKey)
                refreshingKeys.remove(cacheKey)
            }
        }
    }

    private fun <T : Any> loadWithLock(
        key: String,
        cacheKey: String,
        type: Class<T>,
        loader: () -> T?
    ): T? {
        // 전략별로 락 네임스페이스 분리 (전략 간 간섭 방지)
        val lockKey = "refresh:${strategyName}:$key"
        val lockTimeout = Duration.ofSeconds(cacheProperties.lockTimeoutSeconds)
        val retryInterval = Duration.ofMillis(cacheProperties.lockRetryIntervalMs)
        val maxWaitTime = Duration.ofMillis(cacheProperties.lockRetryIntervalMs * cacheProperties.lockMaxRetries)

        val lockAcquired = distributedLock.waitForLock(lockKey, lockTimeout, maxWaitTime, retryInterval)

        if (lockAcquired) {
            try {
                // 락 획득 후 다시 캐시 확인
                val cachedValue = getCachedValueFromCache(cacheKey, type)

                if (cachedValue != null && !cachedValue.isExpired()) {
                    log.debug("[{}] Cache available after lock: key={}", strategyName, cacheKey)
                    return cachedValue.value
                }

                return loadAndCache(cacheKey, loader)
            } finally {
                distributedLock.unlock(lockKey)
            }
        } else {
            log.warn("[{}] Lock acquisition failed: key={}", strategyName, cacheKey)

            // 캐시 재확인
            val cachedValue = getCachedValueFromCache(cacheKey, type)

            if (cachedValue?.value != null) {
                return cachedValue.value
            }

            // Fallback
            log.warn("[{}] Fallback to direct load: key={}", strategyName, cacheKey)
            return loader()
        }
    }

    private fun <T : Any> loadAndCache(cacheKey: String, loader: () -> T?): T? {
        val value = loader() ?: return null

        val jitter = Random.nextLong(0, cacheProperties.jitterMaxSeconds + 1)
        val cachedValue = CachedValue.create(
            value = value,
            baseTtlSeconds = cacheProperties.baseTtlSeconds,
            softTtlRatio = cacheProperties.softTtlRatio,
            jitterSeconds = jitter
        )

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
