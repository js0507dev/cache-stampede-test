package com.example.cachestampede.application.product

import com.example.cachestampede.domain.product.Product
import com.example.cachestampede.domain.product.ProductRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

@Service
class ProductService(
    private val productRepository: ProductRepository,
    meterRegistry: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 외부 의존(예: DB) latency 계측
    private val dbFindByIdTimer: Timer = Timer.builder("dependency.db.latency")
        .tag("operation", "findById")
        .publishPercentileHistogram()
        .register(meterRegistry)

    private val dbFindByCategoryTimer: Timer = Timer.builder("dependency.db.latency")
        .tag("operation", "findByCategory")
        .publishPercentileHistogram()
        .register(meterRegistry)

    @Transactional(readOnly = true)
    fun findById(id: Long): ProductDto? {
        log.debug("Fetching product from database: id={}", id)

        return dbFindByIdTimer.recordCallable {
            // DB 조회를 시뮬레이션하기 위해 약간의 지연 추가 (실제 복잡한 쿼리 시뮬레이션)
            simulateSlowQuery()

            productRepository.findById(id)
                .map { ProductDto.from(it) }
                .orElse(null)
        }
    }

    @Transactional(readOnly = true)
    fun findByCategory(category: String): List<ProductDto> {
        log.debug("Fetching products by category: {}", category)
        return dbFindByCategoryTimer.recordCallable {
            simulateSlowQuery()
            productRepository.findByCategory(category).map { ProductDto.from(it) }
        } ?: emptyList()
    }

    @Transactional
    fun create(
        name: String,
        description: String?,
        price: java.math.BigDecimal,
        stockQuantity: Int,
        category: String
    ): ProductDto {
        val product = Product(
            name = name,
            description = description,
            price = price,
            stockQuantity = stockQuantity,
            category = category
        )
        val saved = productRepository.save(product)
        log.info("Product created: id={}, name={}", saved.id, saved.name)
        return ProductDto.from(saved)
    }

    private fun simulateSlowQuery() {
        // 실제 환경에서 복잡한 쿼리가 실행되는 것을 시뮬레이션
        // 100ms 지연으로 DB 부하 상황 재현
        TimeUnit.MILLISECONDS.sleep(100)
    }
}
