package com.example.cachestampede.infrastructure.cache.lock

import java.time.Duration

interface DistributedLock {
    /**
     * 분산 락 획득 시도
     * @param key 락 키
     * @param ttl 락 만료 시간
     * @return 락 획득 성공 여부
     */
    fun tryLock(key: String, ttl: Duration): Boolean

    /**
     * 분산 락 해제
     * @param key 락 키
     */
    fun unlock(key: String)

    /**
     * 락 획득까지 대기 (타임아웃 있음)
     * @param key 락 키
     * @param ttl 락 만료 시간
     * @param timeout 최대 대기 시간
     * @param retryInterval 재시도 간격
     * @return 락 획득 성공 여부
     */
    fun waitForLock(
        key: String,
        ttl: Duration,
        timeout: Duration,
        retryInterval: Duration
    ): Boolean
}
