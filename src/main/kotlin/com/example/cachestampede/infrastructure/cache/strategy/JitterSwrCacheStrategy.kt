package com.example.cachestampede.infrastructure.cache.strategy

import com.example.cachestampede.infrastructure.cache.CacheProperties
import com.example.cachestampede.infrastructure.cache.CachedValue
import com.example.cachestampede.infrastructure.cache.lock.DistributedLock
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
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
    cacheObjectMapper: ObjectMapper,
    private val distributedLock: DistributedLock,
    meterRegistry: MeterRegistry
) : BaseCacheStrategy(redisTemplate, cacheProperties, cacheObjectMapper) {

    override val strategyName: String = "jitter-swr"

    // 동시에 여러 갱신 요청이 발생하는 것을 방지하기 위한 마커
    private val refreshingKeys = ConcurrentHashMap.newKeySet<String>()

    // ---- Metrics (SWR 관측용) ----
    private val cacheHit: Counter = Counter.builder("cache.swr.cache_hit")
        .tag("strategy", strategyName)
        .tag("state", "fresh")
        .register(meterRegistry)
    private val cacheStaleHit: Counter = Counter.builder("cache.swr.cache_hit")
        .tag("strategy", strategyName)
        .tag("state", "stale")
        .register(meterRegistry)
    private val cacheMiss: Counter = Counter.builder("cache.swr.cache_miss")
        .tag("strategy", strategyName)
        .register(meterRegistry)
    private val revalidateStarted: Counter = Counter.builder("cache.swr.revalidate_started")
        .tag("strategy", strategyName)
        .register(meterRegistry)
    private val revalidateFinished: Counter = Counter.builder("cache.swr.revalidate_finished")
        .tag("strategy", strategyName)
        .register(meterRegistry)
    private val revalidateFailed: Counter = Counter.builder("cache.swr.revalidate_failed")
        .tag("strategy", strategyName)
        .register(meterRegistry)
    private val revalidateDuration: Timer = Timer.builder("cache.swr.revalidate_duration")
        .tag("strategy", strategyName)
        .publishPercentileHistogram()
        .register(meterRegistry)

    override fun <T : Any> getOrLoad(key: String, type: Class<T>, loader: () -> T?): T? {
        val cacheKey = buildCacheKey(key)

        // 1. 캐시 조회 (SWR 래퍼 포함)
        val cachedValue = getCachedValueFromCache(cacheKey, type)

        if (cachedValue != null) {
            when {
                cachedValue.isFresh() -> {
                    log.debug("[{}] Cache HIT (fresh): key={}", strategyName, cacheKey)
                    cacheHit.increment()
                    return cachedValue.value
                }

                cachedValue.isStale() -> {
                    log.debug("[{}] Cache HIT (stale): key={}, triggering background refresh", strategyName, cacheKey)
                    cacheStaleHit.increment()
                    // 백그라운드에서 갱신 트리거
                    triggerBackgroundRefresh(key, cacheKey, type, loader)
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
            cacheMiss.increment()
        }

        // 2. 캐시 미스 또는 Hard TTL 만료 - 동기 갱신 (분산 락으로 stampede 완화)
        return loadWithLock(key, cacheKey, type, loader)
    }

    private fun <T : Any> triggerBackgroundRefresh(
        key: String,
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
            val lockKey = "revalidate:${strategyName}:$key"
            val lockTtl = Duration.ofSeconds(cacheProperties.lockTimeoutSeconds)
            try {
                // stale 구간에서는 "대기"하지 않고, 락을 얻는 경우에만 갱신 수행 (사용자 응답 지연 방지)
                if (!distributedLock.tryLock(lockKey, lockTtl)) {
                    return@runAsync
                }

                revalidateStarted.increment()
                log.debug("[{}] Background refresh started: key={}", strategyName, cacheKey)
                revalidateDuration.recordCallable {
                    loadAndCache(cacheKey, loader)
                }
                log.debug("[{}] Background refresh completed: key={}", strategyName, cacheKey)
                revalidateFinished.increment()
            } catch (e: Exception) {
                log.error("[{}] Background refresh failed: key={}, error={}", strategyName, cacheKey, e.message)
                revalidateFailed.increment()
            } finally {
                distributedLock.unlock(lockKey)
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
        val lockKey = "revalidate:${strategyName}:$key"
        val lockTtl = Duration.ofSeconds(cacheProperties.lockTimeoutSeconds)
        val retryInterval = Duration.ofMillis(cacheProperties.lockRetryIntervalMs)
        val maxRetries = cacheProperties.lockMaxRetries

        // 1) 즉시 락 획득 시도 (대기 없음)
        if (distributedLock.tryLock(lockKey, lockTtl)) {
            try {
                val afterLock = getCachedValueFromCache(cacheKey, type)
                if (afterLock != null && !afterLock.isExpired()) {
                    return afterLock.value
                }
                return loadAndCache(cacheKey, loader)
            } finally {
                distributedLock.unlock(lockKey)
            }
        }

        // 2) 락 획득 실패 -> 짧게 재시도하며 캐시 재확인 (DB 접근 회피)
        repeat(maxRetries) {
            try {
                Thread.sleep(retryInterval.toMillis())
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            val cached = getCachedValueFromCache(cacheKey, type)
            if (cached != null && !cached.isExpired()) {
                return cached.value
            }
            // 짧은 재시도 동안 락을 재시도하지 않고 캐시만 확인 (SWR 특성상 stale면 이미 반환됨)
        }

        // 3) 최종적으로도 캐시에 없으면 마지막 수단으로 로더 호출
        return loader()
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
