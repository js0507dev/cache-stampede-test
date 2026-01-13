package com.example.cachestampede.infrastructure.cache.strategy

import com.example.cachestampede.infrastructure.cache.CacheProperties
import com.example.cachestampede.infrastructure.cache.CachedValue
import com.example.cachestampede.infrastructure.cache.lock.DistributedLock
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.*
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class FullProtectionCacheStrategyTest : DescribeSpec({

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

    describe("FullProtectionCacheStrategy (TTL Jitter + SWR + Lock)") {
        val strategy = FullProtectionCacheStrategy(redisTemplate, cacheProperties, objectMapper, distributedLock, meterRegistry)

        it("[성공] Fresh 상태 - 락 없이 즉시 반환") {
            val now = Instant.now()
            val cachedValue = CachedValue(
                value = "fresh_value",
                softExpireAt = now.plusSeconds(60),
                hardExpireAt = now.plusSeconds(120)
            )
            every { valueOps.get("product:full-protection:1") } returns cachedValue

            val result = strategy.getOrLoad("1", String::class.java) { "new_value" }

            result shouldBe "fresh_value"
            verify(exactly = 0) { distributedLock.tryLock(any(), any()) }
            verify(exactly = 0) { distributedLock.waitForLock(any(), any(), any(), any()) }
        }

        it("[성공] Stale 상태 - stale 값 반환 + 백그라운드에서 락 획득 후 갱신") {
            val now = Instant.now()
            val cachedValue = CachedValue(
                value = "stale_value",
                softExpireAt = now.minusSeconds(10),
                hardExpireAt = now.plusSeconds(60)
            )
            every { valueOps.get("product:full-protection:1") } returns cachedValue
            every { distributedLock.tryLock(any(), any()) } returns true
            every { distributedLock.unlock(any()) } just runs
            every { valueOps.set(any(), any(), any<Duration>()) } just runs

            val result = strategy.getOrLoad("1", String::class.java) { "new_value" }

            result shouldBe "stale_value"
            // 백그라운드 갱신 완료 대기
            Thread.sleep(200)
        }

        it("[성공] Expired 상태 - 락 획득 후 동기 갱신") {
            val now = Instant.now()
            val expiredValue = CachedValue(
                value = "expired_value",
                softExpireAt = now.minusSeconds(120),
                hardExpireAt = now.minusSeconds(60)
            )
            every { valueOps.get("product:full-protection:1") } returns expiredValue andThen expiredValue
            every { distributedLock.waitForLock(any(), any(), any(), any()) } returns true
            every { distributedLock.unlock(any()) } just runs
            every { valueOps.set(any(), any(), any<Duration>()) } just runs

            val result = strategy.getOrLoad("1", String::class.java) { "new_value" }

            result shouldBe "new_value"
            verify { distributedLock.waitForLock("refresh:full-protection:1", any(), any(), any()) }
            verify { distributedLock.unlock("refresh:full-protection:1") }
        }

        it("[성공] 캐시 MISS - 락 획득 후 로드") {
            every { valueOps.get("product:full-protection:1") } returns null andThen null
            every { distributedLock.waitForLock(any(), any(), any(), any()) } returns true
            every { distributedLock.unlock(any()) } just runs
            every { valueOps.set(any(), any(), any<Duration>()) } just runs

            val result = strategy.getOrLoad("1", String::class.java) { "new_value" }

            result shouldBe "new_value"
            verify { distributedLock.waitForLock("refresh:full-protection:1", any(), any(), any()) }
        }

        it("[성공] 전략 이름 확인") {
            strategy.strategyName shouldBe "full-protection"
        }
    }

    describe("동시성 테스트") {
        it("[성공] 동시 요청 시 하나의 요청만 DB 접근 (전체 보호)") {
            val threadCount = 10
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(threadCount)
            val dbCalls = AtomicInteger(0)

            val lockAcquired = AtomicInteger(0)
            val cachedData = AtomicInteger(0)

            every { valueOps.get("product:full-protection:1") } answers {
                if (cachedData.get() > 0) {
                    val now = Instant.now()
                    CachedValue("cached_value", now.plusSeconds(60), now.plusSeconds(120))
                } else null
            }
            every { distributedLock.waitForLock(any(), any(), any(), any()) } answers {
                val acquired = lockAcquired.compareAndSet(0, 1)
                if (!acquired) {
                    Thread.sleep(100)
                }
                acquired
            }
            every { distributedLock.unlock(any()) } answers {
                lockAcquired.set(0)
            }
            every { valueOps.set(any(), any(), any<Duration>()) } answers {
                cachedData.set(1)
            }

            val strategy = FullProtectionCacheStrategy(redisTemplate, cacheProperties, objectMapper, distributedLock)

            repeat(threadCount) {
                executor.submit {
                    try {
                        strategy.getOrLoad("1", String::class.java) {
                            dbCalls.incrementAndGet()
                            Thread.sleep(50)
                            "value"
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await()
            executor.shutdown()

            // 전체 보호 전략: 락이 제대로 동작하면 1회만 호출되어야 함
            dbCalls.get() shouldBe 1
        }
    }
})
