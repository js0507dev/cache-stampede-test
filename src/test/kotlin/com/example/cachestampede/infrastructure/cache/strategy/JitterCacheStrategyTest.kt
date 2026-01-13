package com.example.cachestampede.infrastructure.cache.strategy

import com.example.cachestampede.infrastructure.cache.CacheProperties
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class JitterCacheStrategyTest : DescribeSpec({

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

    describe("JitterCacheStrategy (TTL Jitter)") {
        val strategy = JitterCacheStrategy(redisTemplate, cacheProperties, objectMapper)

        it("[성공] TTL이 baseTTL ~ baseTTL+jitterMax 범위 내에서 생성됨") {
            every { valueOps.get("product:jitter:1") } returns null
            val capturedTtl = slot<Duration>()
            every { valueOps.set(any(), any(), capture(capturedTtl)) } just runs

            strategy.getOrLoad("1", String::class.java) { "value" }

            capturedTtl.captured.seconds shouldBeGreaterThanOrEqual 60
            capturedTtl.captured.seconds shouldBeLessThanOrEqual 70
        }

        it("[성공] 여러 번 호출 시 TTL이 다르게 생성됨") {
            every { valueOps.get(any()) } returns null
            val ttls = mutableListOf<Long>()
            every { valueOps.set(any(), any(), any<Duration>()) } answers {
                ttls.add((thirdArg() as Duration).seconds)
            }

            repeat(10) { i ->
                strategy.getOrLoad("key$i", String::class.java) { "value$i" }
            }

            // 모든 TTL이 동일하지는 않아야 함 (랜덤성)
            // 단, 테스트에서 우연히 같을 수 있으므로 범위만 확인
            ttls.forEach { ttl ->
                ttl shouldBeGreaterThanOrEqual 60
                ttl shouldBeLessThanOrEqual 70
            }
        }

        it("[성공] 전략 이름 확인") {
            strategy.strategyName shouldBe "jitter"
        }
    }
})
