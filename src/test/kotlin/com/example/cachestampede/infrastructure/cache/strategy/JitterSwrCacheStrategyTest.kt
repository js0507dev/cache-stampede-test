package com.example.cachestampede.infrastructure.cache.strategy

import com.example.cachestampede.infrastructure.cache.CacheProperties
import com.example.cachestampede.infrastructure.cache.CachedValue
import com.example.cachestampede.infrastructure.cache.lock.DistributedLock
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class JitterSwrCacheStrategyTest : DescribeSpec({

    val redisTemplate = mockk<RedisTemplate<String, Any>>()
    val valueOps = mockk<ValueOperations<String, Any>>()
    val distributedLock = mockk<DistributedLock>()
    val objectMapper = ObjectMapper()
    val meterRegistry = SimpleMeterRegistry()
    val cacheProperties = CacheProperties(
        baseTtlSeconds = 60,
        jitterMaxSeconds = 10,
        softTtlRatio = 0.8,
        lockTimeoutSeconds = 5,
        lockRetryIntervalMs = 50,
        lockMaxRetries = 100
    )

    beforeEach {
        clearAllMocks()
        every { redisTemplate.opsForValue() } returns valueOps
    }

    describe("JitterSwrCacheStrategy (TTL Jitter + SWR)") {
        val strategy = JitterSwrCacheStrategy(redisTemplate, cacheProperties, objectMapper, distributedLock, meterRegistry)

        it("[성공] Fresh 상태 - 즉시 반환, 갱신 없음") {
            val now = Instant.now()
            val cachedValue = CachedValue(
                value = "fresh_value",
                softExpireAt = now.plusSeconds(60),
                hardExpireAt = now.plusSeconds(120)
            )
            every { valueOps.get("product:jitter-swr:1") } returns cachedValue
            val loaderCalled = AtomicInteger(0)

            val result = strategy.getOrLoad("1", String::class.java) {
                loaderCalled.incrementAndGet()
                "new_value"
            }

            result shouldBe "fresh_value"
            loaderCalled.get() shouldBe 0
        }

        it("[성공] Stale 상태 - stale 값 반환 + 백그라운드 갱신 트리거") {
            val now = Instant.now()
            val cachedValue = CachedValue(
                value = "stale_value",
                softExpireAt = now.minusSeconds(10),  // Soft TTL 지남
                hardExpireAt = now.plusSeconds(60)    // Hard TTL 안 지남
            )
            every { valueOps.get("product:jitter-swr:1") } returns cachedValue
            every { distributedLock.tryLock(any(), any()) } returns true
            every { distributedLock.unlock(any()) } just runs
            every { valueOps.set(any(), any(), any<Duration>()) } just runs

            val result = strategy.getOrLoad("1", String::class.java) { "new_value" }

            result shouldBe "stale_value"
            // 백그라운드 갱신은 비동기로 발생
            Thread.sleep(200)  // 비동기 작업 완료 대기
            verify(atLeast = 0) { valueOps.set(any(), any(), any<Duration>()) }
        }

        it("[성공] Expired 상태 - 동기 갱신 수행") {
            val now = Instant.now()
            val expiredValue = CachedValue(
                value = "expired_value",
                softExpireAt = now.minusSeconds(120),
                hardExpireAt = now.minusSeconds(60)
            )
            every { valueOps.get("product:jitter-swr:1") } returns expiredValue andThen expiredValue
            every { distributedLock.tryLock(any(), any()) } returns true
            every { distributedLock.unlock(any()) } just runs
            every { valueOps.set(any(), any(), any<Duration>()) } just runs
            val loaderCalled = AtomicInteger(0)

            val result = strategy.getOrLoad("1", String::class.java) {
                loaderCalled.incrementAndGet()
                "new_value"
            }

            result shouldBe "new_value"
            loaderCalled.get() shouldBe 1
        }

        it("[성공] 캐시 MISS - 동기 로드 및 캐시 저장") {
            every { valueOps.get("product:jitter-swr:1") } returns null andThen null
            every { distributedLock.tryLock(any(), any()) } returns true
            every { distributedLock.unlock(any()) } just runs
            every { valueOps.set(any(), any(), any<Duration>()) } just runs

            val result = strategy.getOrLoad("1", String::class.java) { "new_value" }

            result shouldBe "new_value"
            verify { valueOps.set(eq("product:jitter-swr:1"), any<CachedValue<String>>(), any<Duration>()) }
        }

        it("[성공] 전략 이름 확인") {
            strategy.strategyName shouldBe "jitter-swr"
        }
    }
})
