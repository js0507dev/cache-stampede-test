package com.example.cachestampede.infrastructure.cache

import java.io.Serializable
import java.time.Instant

/**
 * SWR(Stale-While-Revalidate) 전략을 위한 캐시 래퍼
 *
 * @param value 캐시된 실제 값
 * @param softExpireAt Soft TTL - 이 시간 이후 백그라운드 갱신 시작
 * @param hardExpireAt Hard TTL - 이 시간 이후 반드시 동기 갱신 필요
 */
data class CachedValue<T>(
    val value: T,
    val softExpireAt: Instant,
    val hardExpireAt: Instant
) : Serializable {

    /**
     * 캐시가 완전히 유효한지 (Soft TTL 이전)
     */
    fun isFresh(): Boolean = Instant.now().isBefore(softExpireAt)

    /**
     * 캐시가 stale 상태인지 (Soft TTL ~ Hard TTL 사이)
     * 값은 반환하되 백그라운드 갱신이 필요
     */
    fun isStale(): Boolean {
        val now = Instant.now()
        return now.isAfter(softExpireAt) && now.isBefore(hardExpireAt)
    }

    /**
     * 캐시가 완전히 만료되었는지 (Hard TTL 이후)
     */
    fun isExpired(): Boolean = Instant.now().isAfter(hardExpireAt)

    companion object {
        fun <T> create(
            value: T,
            baseTtlSeconds: Long,
            softTtlRatio: Double,
            jitterSeconds: Long = 0
        ): CachedValue<T> {
            val now = Instant.now()
            val totalTtl = baseTtlSeconds + jitterSeconds
            val softTtl = (totalTtl * softTtlRatio).toLong()

            return CachedValue(
                value = value,
                softExpireAt = now.plusSeconds(softTtl),
                hardExpireAt = now.plusSeconds(totalTtl)
            )
        }
    }
}
