package com.example.cachestampede.infrastructure.cache.lock

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class RedisDistributedLock(
    private val redisTemplate: RedisTemplate<String, Any>
) : DistributedLock {

    private val log = LoggerFactory.getLogger(javaClass)

    // 스레드별 락 식별자 저장 (재진입 방지 및 안전한 해제를 위함)
    private val lockValues = ThreadLocal<MutableMap<String, String>>()

    private fun getLockValue(key: String): String? =
        lockValues.get()?.get(key)

    private fun setLockValue(key: String, value: String) {
        val map = lockValues.get() ?: mutableMapOf<String, String>().also { lockValues.set(it) }
        map[key] = value
    }

    private fun removeLockValue(key: String) {
        lockValues.get()?.remove(key)
    }

    override fun tryLock(key: String, ttl: Duration): Boolean {
        val lockKey = "lock:$key"
        val lockValue = UUID.randomUUID().toString()

        val acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, lockValue, ttl) ?: false

        if (acquired) {
            setLockValue(lockKey, lockValue)
            log.debug("Lock acquired: key={}", lockKey)
        }

        return acquired
    }

    override fun unlock(key: String) {
        val lockKey = "lock:$key"
        val expectedValue = getLockValue(lockKey) ?: return

        // Lua 스크립트로 원자적 삭제 (자신의 락만 삭제)
        val script = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            else
                return 0
            end
        """.trimIndent()

        try {
            redisTemplate.execute(
                org.springframework.data.redis.core.script.DefaultRedisScript(script, Long::class.java),
                listOf(lockKey),
                expectedValue
            )
            log.debug("Lock released: key={}", lockKey)
        } finally {
            removeLockValue(lockKey)
        }
    }

    override fun waitForLock(
        key: String,
        ttl: Duration,
        timeout: Duration,
        retryInterval: Duration
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeout.toMillis()

        while (System.currentTimeMillis() < deadline) {
            if (tryLock(key, ttl)) {
                return true
            }

            try {
                Thread.sleep(retryInterval.toMillis())
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        log.warn("Lock acquisition timeout: key={}, timeout={}ms", key, timeout.toMillis())
        return false
    }
}
