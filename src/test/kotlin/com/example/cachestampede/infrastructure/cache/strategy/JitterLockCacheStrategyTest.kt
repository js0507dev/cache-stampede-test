package com.example.cachestampede.infrastructure.cache.strategy

import com.example.cachestampede.infrastructure.cache.CacheProperties
import com.example.cachestampede.infrastructure.cache.lock.DistributedLock
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class JitterLockCacheStrategyTest : DescribeSpec({

    val redisTemplate = mockk<RedisTemplate<String, Any>>()
    val valueOps = mockk<ValueOperations<String, Any>>()
    val distributedLock = mockk<DistributedLock>()
    val objectMapper = ObjectMapper()
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

    describe("JitterLockCacheStrategy (TTL Jitter + Lock)") {
        val strategy = JitterLockCacheStrategy(redisTemplate, cacheProperties, objectMapper, distributedLock)

        it("[성공] 캐시 HIT - 락 획득 없이 캐시 값 반환") {
            every { valueOps.get("product:1") } returns "cached_value"

            val result = strategy.getOrLoad("1", String::class.java) { "new_value" }

            result shouldBe "cached_value"
            verify(exactly = 0) { distributedLock.waitForLock(any(), any(), any(), any()) }
        }

        it("[성공] 캐시 MISS + 락 획득 성공 - DB 조회 후 캐시 저장") {
            every { valueOps.get("product:1") } returns null andThen null  // 첫 조회, 락 후 조회
            every { distributedLock.waitForLock(any(), any(), any(), any()) } returns true
            every { distributedLock.unlock(any()) } just runs
            every { valueOps.set(any(), any(), any<Duration>()) } just runs
            val loaderCalled = AtomicInteger(0)

            val result = strategy.getOrLoad("1", String::class.java) {
                loaderCalled.incrementAndGet()
                "new_value"
            }

            result shouldBe "new_value"
            loaderCalled.get() shouldBe 1
            verify { distributedLock.waitForLock("refresh:1", any(), any(), any()) }
            verify { distributedLock.unlock("refresh:1") }
        }

        it("[성공] 락 획득 후 캐시가 이미 갱신된 경우 - DB 조회하지 않음") {
            every { valueOps.get("product:1") } returns null andThen "cached_by_other"
            every { distributedLock.waitForLock(any(), any(), any(), any()) } returns true
            every { distributedLock.unlock(any()) } just runs
            val loaderCalled = AtomicInteger(0)

            val result = strategy.getOrLoad("1", String::class.java) {
                loaderCalled.incrementAndGet()
                "new_value"
            }

            result shouldBe "cached_by_other"
            loaderCalled.get() shouldBe 0
        }

        it("[성공] 락 획득 실패 + 대기 후 캐시에 값 있음 - 캐시 값 반환") {
            every { valueOps.get("product:1") } returns null andThen "cached_value"
            every { distributedLock.waitForLock(any(), any(), any(), any()) } returns false

            val result = strategy.getOrLoad("1", String::class.java) { "new_value" }

            result shouldBe "cached_value"
        }

        it("[실패] 락 획득 실패 + 캐시에도 값 없음 - Fallback으로 직접 로드") {
            every { valueOps.get("product:1") } returns null andThen null
            every { distributedLock.waitForLock(any(), any(), any(), any()) } returns false

            val result = strategy.getOrLoad("1", String::class.java) { "fallback_value" }

            result shouldBe "fallback_value"
        }

        it("[성공] 전략 이름 확인") {
            strategy.strategyName shouldBe "jitter-lock"
        }
    }

    describe("동시성 테스트") {
        it("[성공] 동시 요청 시 하나의 요청만 DB 접근") {
            val threadCount = 10
            val executor = Executors.newFixedThreadPool(threadCount)
            val latch = CountDownLatch(threadCount)
            val dbCalls = AtomicInteger(0)

            // 실제 락 동작을 시뮬레이션하는 모의 객체
            val lockAcquired = AtomicInteger(0)
            every { valueOps.get("product:1") } answers {
                if (lockAcquired.get() > 0) "cached_value" else null
            }
            every { distributedLock.waitForLock(any(), any(), any(), any()) } answers {
                val acquired = lockAcquired.compareAndSet(0, 1)
                if (!acquired) {
                    // 다른 스레드가 락을 가지고 있으면 잠시 대기
                    Thread.sleep(100)
                }
                acquired
            }
            every { distributedLock.unlock(any()) } answers {
                lockAcquired.set(0)
            }
            every { valueOps.set(any(), any(), any<Duration>()) } just runs

            val strategy = JitterLockCacheStrategy(redisTemplate, cacheProperties, objectMapper, distributedLock)

            repeat(threadCount) {
                executor.submit {
                    try {
                        strategy.getOrLoad("1", String::class.java) {
                            dbCalls.incrementAndGet()
                            Thread.sleep(50)  // DB 조회 시뮬레이션
                            "value"
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            latch.await()
            executor.shutdown()

            // 락이 제대로 동작하면 1회만 호출되어야 함
            dbCalls.get() shouldBe 1
        }
    }
})
