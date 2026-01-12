package com.example.cachestampede.interfaces.api

import com.example.cachestampede.application.product.ProductDto
import com.example.cachestampede.application.product.ProductService
import com.example.cachestampede.infrastructure.cache.strategy.*
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/products")
class ProductController(
    private val productService: ProductService,
    private val basicCacheStrategy: BaseCacheStrategy,
    private val jitterCacheStrategy: JitterCacheStrategy,
    private val jitterSwrCacheStrategy: JitterSwrCacheStrategy,
    private val jitterLockCacheStrategy: JitterLockCacheStrategy,
    private val fullProtectionCacheStrategy: FullProtectionCacheStrategy
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 1. 캐시 없음 - 매번 DB 직접 조회 (기준선)
     */
    @GetMapping("/{id}/no-cache")
    fun getProductNoCache(@PathVariable id: Long): ResponseEntity<ProductResponse> {
        log.debug("Request: no-cache, id={}", id)
        val startTime = System.currentTimeMillis()

        val product = productService.findById(id)
            ?: return ResponseEntity.notFound().build()

        val duration = System.currentTimeMillis() - startTime
        return ResponseEntity.ok(ProductResponse.from(product, "no-cache", duration))
    }

    /**
     * 2. 기본 캐시 - 스탬피드 대응 없음
     */
    @GetMapping("/{id}/basic")
    fun getProductBasic(@PathVariable id: Long): ResponseEntity<ProductResponse> {
        log.debug("Request: basic, id={}", id)
        val startTime = System.currentTimeMillis()

        val product = basicCacheStrategy.getOrLoad(
            key = id.toString(),
            type = ProductDto::class.java
        ) { productService.findById(id) }
            ?: return ResponseEntity.notFound().build()

        val duration = System.currentTimeMillis() - startTime
        return ResponseEntity.ok(ProductResponse.from(product, "basic", duration))
    }

    /**
     * 3. TTL Jitter만 적용
     */
    @GetMapping("/{id}/jitter")
    fun getProductJitter(@PathVariable id: Long): ResponseEntity<ProductResponse> {
        log.debug("Request: jitter, id={}", id)
        val startTime = System.currentTimeMillis()

        val product = jitterCacheStrategy.getOrLoad(
            key = id.toString(),
            type = ProductDto::class.java
        ) { productService.findById(id) }
            ?: return ResponseEntity.notFound().build()

        val duration = System.currentTimeMillis() - startTime
        return ResponseEntity.ok(ProductResponse.from(product, "jitter", duration))
    }

    /**
     * 4. TTL Jitter + SWR
     */
    @GetMapping("/{id}/jitter-swr")
    fun getProductJitterSwr(@PathVariable id: Long): ResponseEntity<ProductResponse> {
        log.debug("Request: jitter-swr, id={}", id)
        val startTime = System.currentTimeMillis()

        val product = jitterSwrCacheStrategy.getOrLoad(
            key = id.toString(),
            type = ProductDto::class.java
        ) { productService.findById(id) }
            ?: return ResponseEntity.notFound().build()

        val duration = System.currentTimeMillis() - startTime
        return ResponseEntity.ok(ProductResponse.from(product, "jitter-swr", duration))
    }

    /**
     * 5. TTL Jitter + Distributed Lock
     */
    @GetMapping("/{id}/jitter-lock")
    fun getProductJitterLock(@PathVariable id: Long): ResponseEntity<ProductResponse> {
        log.debug("Request: jitter-lock, id={}", id)
        val startTime = System.currentTimeMillis()

        val product = jitterLockCacheStrategy.getOrLoad(
            key = id.toString(),
            type = ProductDto::class.java
        ) { productService.findById(id) }
            ?: return ResponseEntity.notFound().build()

        val duration = System.currentTimeMillis() - startTime
        return ResponseEntity.ok(ProductResponse.from(product, "jitter-lock", duration))
    }

    /**
     * 6. 전체 보호: TTL Jitter + SWR + Lock
     */
    @GetMapping("/{id}/full")
    fun getProductFull(@PathVariable id: Long): ResponseEntity<ProductResponse> {
        log.debug("Request: full-protection, id={}", id)
        val startTime = System.currentTimeMillis()

        val product = fullProtectionCacheStrategy.getOrLoad(
            key = id.toString(),
            type = ProductDto::class.java
        ) { productService.findById(id) }
            ?: return ResponseEntity.notFound().build()

        val duration = System.currentTimeMillis() - startTime
        return ResponseEntity.ok(ProductResponse.from(product, "full-protection", duration))
    }

    /**
     * 캐시 무효화 (테스트용)
     */
    @DeleteMapping("/{id}/cache")
    fun invalidateCache(@PathVariable id: Long): ResponseEntity<Map<String, String>> {
        val key = id.toString()
        basicCacheStrategy.invalidate(key)
        jitterCacheStrategy.invalidate(key)
        jitterSwrCacheStrategy.invalidate(key)
        jitterLockCacheStrategy.invalidate(key)
        fullProtectionCacheStrategy.invalidate(key)

        return ResponseEntity.ok(mapOf("message" to "Cache invalidated for product $id"))
    }
}
