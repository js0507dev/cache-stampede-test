package com.example.cachestampede.infrastructure.cache.strategy

/**
 * 캐시 전략 인터페이스
 */
interface CacheStrategy {
    /**
     * 캐시에서 값을 조회하거나, 없으면 loader를 실행하여 값을 가져옴
     *
     * @param key 캐시 키
     * @param loader 캐시 미스 시 실행할 데이터 로더
     * @return 캐시된 값 또는 새로 로드된 값
     */
    fun <T : Any> getOrLoad(key: String, type: Class<T>, loader: () -> T?): T?

    /**
     * 캐시 무효화
     */
    fun invalidate(key: String)

    /**
     * 전략 이름
     */
    val strategyName: String
}
