package com.example.cachestampede.infrastructure.cache.strategy

import com.example.cachestampede.infrastructure.cache.CachedValue
import com.fasterxml.jackson.databind.ObjectMapper
import com.example.cachestampede.infrastructure.cache.CacheProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import java.time.Duration
import kotlin.random.Random

/**
 * 기본 캐시 전략 - 스탬피드 방지 없음
 * 단순 캐시 조회/저장만 수행
 */
open class BaseCacheStrategy(
    protected val redisTemplate: RedisTemplate<String, Any>,
    protected val cacheProperties: CacheProperties,
    protected val cacheObjectMapper: ObjectMapper
) : CacheStrategy {

    protected val log = LoggerFactory.getLogger(javaClass)

    override val strategyName: String = "basic"

    override fun <T : Any> getOrLoad(key: String, type: Class<T>, loader: () -> T?): T? {
        val cacheKey = buildCacheKey(key)

        // 1. 캐시 조회
        val cached = getFromCache(cacheKey, type)
        if (cached != null) {
            log.debug("[{}] Cache HIT: key={}", strategyName, cacheKey)
            return cached
        }

        log.debug("[{}] Cache MISS: key={}", strategyName, cacheKey)

        // 2. 데이터 로드
        val value = loader() ?: return null

        // 3. 캐시 저장
        saveToCache(cacheKey, value, getTtl())
        log.debug("[{}] Cache SET: key={}, ttl={}s", strategyName, cacheKey, getTtl().seconds)

        return value
    }

    override fun invalidate(key: String) {
        val cacheKey = buildCacheKey(key)
        redisTemplate.delete(cacheKey)
        log.debug("[{}] Cache INVALIDATE: key={}", strategyName, cacheKey)
    }

    /**
     * 전략 비교/시뮬레이션을 위해 전략별로 캐시 네임스페이스를 분리한다.
     *
     * 그렇지 않으면:
     * - basic/jitter는 ProductDto를 저장
     * - jitter-swr/full은 CachedValue<ProductDto>를 저장
     * 처럼 값 타입이 달라 서로의 캐시를 읽다가 ClassCastException/타입 오염이 발생한다.
     *
     * 또한 같은 키를 공유하면 "전략 비교" 자체가 성립하지 않는다.
     */
    protected fun buildCacheKey(key: String): String = "product:${strategyName}:$key"

    @Suppress("UNCHECKED_CAST")
    protected fun <T> getFromCache(key: String, type: Class<T>): T? {
        return try {
            val raw = redisTemplate.opsForValue().get(key) ?: return null

            // 1) 이미 원하는 타입이면 그대로 반환
            if (type.isInstance(raw)) {
                return type.cast(raw)
            }

            // 2) 타입 정보 없이 Map 등으로 역직렬화된 경우(LinkedHashMap → DTO) 안전 변환
            return cacheObjectMapper.convertValue(raw, type)
        } catch (e: Exception) {
            log.warn("Failed to get from cache: key={}, type={}, error={}", key, type.simpleName, e.message)
            null
        }
    }

    protected fun <T : Any> getCachedValueFromCache(key: String, valueType: Class<T>): CachedValue<T>? {
        return try {
            val raw = redisTemplate.opsForValue().get(key) ?: return null

            @Suppress("UNCHECKED_CAST")
            if (raw is CachedValue<*>) {
                // CachedValue로 역직렬화되었지만, 내부 value가 LinkedHashMap일 수 있음
                val cachedValue = raw as CachedValue<*>
                val value = if (valueType.isInstance(cachedValue.value)) {
                    valueType.cast(cachedValue.value)
                } else {
                    // value가 Map 등으로 역직렬화된 경우 → DTO로 변환
                    cacheObjectMapper.convertValue(cachedValue.value, valueType)
                }
                return CachedValue(value, cachedValue.softExpireAt, cachedValue.hardExpireAt)
            }

            // CachedValue가 아닌 경우 (Map 등으로 역직렬화된 경우)
            val javaType = cacheObjectMapper.typeFactory
                .constructParametricType(CachedValue::class.java, valueType)
            cacheObjectMapper.convertValue(raw, javaType)
        } catch (e: Exception) {
            log.warn(
                "Failed to get CachedValue from cache: key={}, valueType={}, error={}",
                key,
                valueType.simpleName,
                e.message,
                e
            )
            null
        }
    }

    protected fun <T : Any> saveToCache(key: String, value: T, ttl: Duration) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl)
        } catch (e: Exception) {
            log.warn("Failed to save to cache: key={}, error={}", key, e.message)
        }
    }

    protected open fun getTtl(): Duration =
        Duration.ofSeconds(cacheProperties.baseTtlSeconds)

    protected fun getJitteredTtl(): Duration {
        val jitter = Random.nextLong(0, cacheProperties.jitterMaxSeconds + 1)
        return Duration.ofSeconds(cacheProperties.baseTtlSeconds + jitter)
    }
}
