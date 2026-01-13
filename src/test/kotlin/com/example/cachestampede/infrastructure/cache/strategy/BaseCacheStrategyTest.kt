package com.example.cachestampede.infrastructure.cache.strategy

import com.example.cachestampede.infrastructure.cache.CacheProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class BaseCacheStrategyTest : DescribeSpec({

    val redisTemplate = mockk<RedisTemplate<String, Any>>()
    val valueOps = mockk<ValueOperations<String, Any>>()
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

    describe("BaseCacheStrategy (기본 캐시)") {
        val strategy = BaseCacheStrategy(redisTemplate, cacheProperties, objectMapper)

        it("[성공] 캐시 HIT - 캐시된 값 반환") {
            every { valueOps.get("product:basic:1") } returns "cached_value"
            val loaderCalled = AtomicInteger(0)

            val result = strategy.getOrLoad("1", String::class.java) {
                loaderCalled.incrementAndGet()
                "new_value"
            }

            result shouldBe "cached_value"
            loaderCalled.get() shouldBe 0
        }

        it("[성공] 캐시 MISS - loader 호출 후 캐시 저장") {
            every { valueOps.get("product:basic:1") } returns null
            every { valueOps.set(any(), any(), any<Duration>()) } just runs
            val loaderCalled = AtomicInteger(0)

            val result = strategy.getOrLoad("1", String::class.java) {
                loaderCalled.incrementAndGet()
                "new_value"
            }

            result shouldBe "new_value"
            loaderCalled.get() shouldBe 1
            verify { valueOps.set("product:basic:1", "new_value", Duration.ofSeconds(60)) }
        }

        it("[성공] loader가 null 반환 시 캐시 저장하지 않음") {
            every { valueOps.get("product:basic:1") } returns null

            val result = strategy.getOrLoad("1", String::class.java) { null }

            result shouldBe null
            verify(exactly = 0) { valueOps.set(any(), any(), any<Duration>()) }
        }

        it("[성공] 캐시 무효화") {
            every { redisTemplate.delete("product:basic:1") } returns true

            strategy.invalidate("1")

            verify { redisTemplate.delete("product:basic:1") }
        }
    }
})
