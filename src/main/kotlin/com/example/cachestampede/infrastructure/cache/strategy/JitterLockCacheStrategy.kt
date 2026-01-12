package com.example.cachestampede.infrastructure.cache.strategy

import com.example.cachestampede.infrastructure.cache.CacheProperties
import com.example.cachestampede.infrastructure.cache.lock.DistributedLock
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration

/**
 * TTL Jitter + Distributed Lock 전략
 * - 캐시 미스 시 분산 락을 사용하여 하나의 요청만 DB에 접근
 * - 다른 요청들은 락 해제 후 캐시에서 값을 가져옴
 */
class JitterLockCacheStrategy(
    redisTemplate: RedisTemplate<String, Any>,
    cacheProperties: CacheProperties,
    cacheObjectMapper: ObjectMapper,
    private val distributedLock: DistributedLock
) : BaseCacheStrategy(redisTemplate, cacheProperties, cacheObjectMapper) {

    override val strategyName: String = "jitter-lock"

    override fun <T : Any> getOrLoad(key: String, type: Class<T>, loader: () -> T?): T? {
        val cacheKey = buildCacheKey(key)

        // 1. 첫 번째 캐시 조회
        val cached = getFromCache(cacheKey, type)
        if (cached != null) {
            log.debug("[{}] Cache HIT: key={}", strategyName, cacheKey)
            return cached
        }

        log.debug("[{}] Cache MISS, attempting lock: key={}", strategyName, cacheKey)

        // 2. 분산 락 획득 시도
        // 전략별로 락 네임스페이스도 분리 (전략 간 간섭 방지)
        val lockKey = "refresh:${strategyName}:$key"
        val lockTimeout = Duration.ofSeconds(cacheProperties.lockTimeoutSeconds)
        val retryInterval = Duration.ofMillis(cacheProperties.lockRetryIntervalMs)
        val maxWaitTime = Duration.ofMillis(cacheProperties.lockRetryIntervalMs * cacheProperties.lockMaxRetries)

        val lockAcquired = distributedLock.waitForLock(lockKey, lockTimeout, maxWaitTime, retryInterval)

        if (lockAcquired) {
            try {
                // 3. 락 획득 후 다시 캐시 확인 (다른 스레드가 이미 갱신했을 수 있음)
                val cachedAfterLock = getFromCache(cacheKey, type)
                if (cachedAfterLock != null) {
                    log.debug("[{}] Cache HIT after lock: key={}", strategyName, cacheKey)
                    return cachedAfterLock
                }

                // 4. 데이터 로드 및 캐시 저장
                log.debug("[{}] Loading from source: key={}", strategyName, cacheKey)
                val value = loader() ?: return null

                val ttl = getJitteredTtl()
                saveToCache(cacheKey, value, ttl)
                log.debug("[{}] Cache SET: key={}, ttl={}s", strategyName, cacheKey, ttl.seconds)

                return value
            } finally {
                distributedLock.unlock(lockKey)
            }
        } else {
            // 5. 락 획득 실패 - 다시 캐시 확인 (대기 중 다른 스레드가 갱신했을 수 있음)
            log.warn("[{}] Lock acquisition failed, checking cache: key={}", strategyName, cacheKey)
            val cachedAfterWait = getFromCache(cacheKey, type)
            if (cachedAfterWait != null) {
                return cachedAfterWait
            }

            // 캐시에도 없으면 직접 로드 (fallback)
            log.warn("[{}] Fallback to direct load: key={}", strategyName, cacheKey)
            return loader()
        }
    }
}
