package com.example.cachestampede.infrastructure.cache

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer
import org.springframework.data.redis.serializer.StringRedisSerializer

@Configuration
@EnableConfigurationProperties(CacheProperties::class)
class CacheConfig {

    @Bean
    fun cacheObjectMapper(): ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        // 알 수 없는 프로퍼티 무시 (기존 캐시에 isStale/isFresh/isExpired 필드가 있을 수 있음)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        // Redis에 Any(Object)로 저장되는 값을 다시 원래 타입(ProductDto, CachedValue<*>)으로 복원하려면
        // 타입 정보가 필요하다. (기존 캐시 값이 Map으로 들어간 경우엔 BaseCacheStrategy에서 추가로 convertValue로 복구)
        .activateDefaultTyping(
            BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.example.cachestampede")
                .allowIfSubType("java.time")
                .allowIfSubType("java.math")
                .allowIfSubType("java.util")
                .allowIfSubType("kotlin")
                .build(),
            ObjectMapper.DefaultTyping.EVERYTHING,
            JsonTypeInfo.As.PROPERTY
        )

    @Bean
    fun redisTemplate(
        connectionFactory: RedisConnectionFactory,
        cacheObjectMapper: ObjectMapper
    ): RedisTemplate<String, Any> {
        val template = RedisTemplate<String, Any>()
        template.connectionFactory = connectionFactory
        template.keySerializer = StringRedisSerializer()
        template.valueSerializer = GenericJackson2JsonRedisSerializer(cacheObjectMapper)
        template.hashKeySerializer = StringRedisSerializer()
        template.hashValueSerializer = GenericJackson2JsonRedisSerializer(cacheObjectMapper)
        template.afterPropertiesSet()
        return template
    }
}
