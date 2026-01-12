package com.example.cachestampede.infrastructure.cache.lock

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration

class RedisDistributedLockTest : DescribeSpec({

    val redisTemplate = mockk<RedisTemplate<String, Any>>()
    val valueOps = mockk<ValueOperations<String, Any>>()

    beforeEach {
        clearAllMocks()
        every { redisTemplate.opsForValue() } returns valueOps
    }

    describe("RedisDistributedLock") {
        val lock = RedisDistributedLock(redisTemplate)

        describe("tryLock") {
            it("[성공] 락 획득 성공") {
                every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns true

                val result = lock.tryLock("test-key", Duration.ofSeconds(5))

                result shouldBe true
                verify { valueOps.setIfAbsent(eq("lock:test-key"), any(), eq(Duration.ofSeconds(5))) }
            }

            it("[실패] 락 획득 실패 - 이미 다른 프로세스가 락 보유") {
                every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns false

                val result = lock.tryLock("test-key", Duration.ofSeconds(5))

                result shouldBe false
            }
        }

        describe("unlock") {
            it("[성공] 락 해제 - 자신의 락만 해제") {
                // 먼저 락 획득
                every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns true
                lock.tryLock("test-key", Duration.ofSeconds(5))

                // Lua 스크립트 실행 모킹
                every { redisTemplate.execute(any<DefaultRedisScript<Long>>(), any<List<String>>(), any()) } returns 1L

                lock.unlock("test-key")

                verify { redisTemplate.execute(any<DefaultRedisScript<Long>>(), eq(listOf("lock:test-key")), any()) }
            }
        }

        describe("waitForLock") {
            it("[성공] 첫 번째 시도에서 락 획득") {
                every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns true

                val result = lock.waitForLock(
                    key = "test-key",
                    ttl = Duration.ofSeconds(5),
                    timeout = Duration.ofSeconds(10),
                    retryInterval = Duration.ofMillis(100)
                )

                result shouldBe true
            }

            it("[성공] 재시도 후 락 획득") {
                var attempts = 0
                every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } answers {
                    attempts++
                    attempts >= 3  // 3번째 시도에서 성공
                }

                val result = lock.waitForLock(
                    key = "test-key",
                    ttl = Duration.ofSeconds(5),
                    timeout = Duration.ofSeconds(10),
                    retryInterval = Duration.ofMillis(50)
                )

                result shouldBe true
            }

            it("[실패] 타임아웃으로 인한 락 획득 실패") {
                every { valueOps.setIfAbsent(any(), any(), any<Duration>()) } returns false

                val result = lock.waitForLock(
                    key = "test-key",
                    ttl = Duration.ofSeconds(5),
                    timeout = Duration.ofMillis(200),
                    retryInterval = Duration.ofMillis(50)
                )

                result shouldBe false
            }
        }
    }
})
