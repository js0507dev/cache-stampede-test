package com.example.cachestampede.infrastructure.cache

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class CachedValueTest : DescribeSpec({

    describe("CachedValue 생성") {
        it("[성공] 기본 TTL과 Soft TTL 비율로 생성") {
            val value = CachedValue.create(
                value = "test",
                baseTtlSeconds = 60,
                softTtlRatio = 0.8,
                jitterSeconds = 0
            )

            value.value shouldBe "test"
            value.isFresh() shouldBe true
            value.isStale() shouldBe false
            value.isExpired() shouldBe false
        }

        it("[성공] Jitter가 포함된 TTL 생성") {
            val value = CachedValue.create(
                value = "test",
                baseTtlSeconds = 60,
                softTtlRatio = 0.8,
                jitterSeconds = 10
            )

            // Soft TTL = (60 + 10) * 0.8 = 56초
            // Hard TTL = 60 + 10 = 70초
            value.isFresh() shouldBe true
        }
    }

    describe("CachedValue 상태 확인") {
        it("[성공] Fresh 상태 (Soft TTL 이전)") {
            val now = Instant.now()
            val value = CachedValue(
                value = "test",
                softExpireAt = now.plusSeconds(60),
                hardExpireAt = now.plusSeconds(120)
            )

            value.isFresh() shouldBe true
            value.isStale() shouldBe false
            value.isExpired() shouldBe false
        }

        it("[성공] Stale 상태 (Soft ~ Hard TTL 사이)") {
            val now = Instant.now()
            val value = CachedValue(
                value = "test",
                softExpireAt = now.minusSeconds(10),  // Soft TTL 지남
                hardExpireAt = now.plusSeconds(60)    // Hard TTL 안 지남
            )

            value.isFresh() shouldBe false
            value.isStale() shouldBe true
            value.isExpired() shouldBe false
        }

        it("[성공] Expired 상태 (Hard TTL 이후)") {
            val now = Instant.now()
            val value = CachedValue(
                value = "test",
                softExpireAt = now.minusSeconds(120),
                hardExpireAt = now.minusSeconds(60)
            )

            value.isFresh() shouldBe false
            value.isStale() shouldBe false
            value.isExpired() shouldBe true
        }
    }
})
