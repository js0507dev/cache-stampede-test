package com.example.cachestampede.interfaces.api

import com.example.cachestampede.application.product.ProductDto
import java.math.BigDecimal
import java.time.Instant

data class ProductResponse(
    val id: Long,
    val name: String,
    val description: String?,
    val price: BigDecimal,
    val stockQuantity: Int,
    val category: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val meta: ResponseMeta
) {
    data class ResponseMeta(
        val strategy: String,
        val responseTimeMs: Long
    )

    companion object {
        fun from(product: ProductDto, strategy: String, responseTimeMs: Long): ProductResponse {
            return ProductResponse(
                id = product.id,
                name = product.name,
                description = product.description,
                price = product.price,
                stockQuantity = product.stockQuantity,
                category = product.category,
                createdAt = product.createdAt,
                updatedAt = product.updatedAt,
                meta = ResponseMeta(
                    strategy = strategy,
                    responseTimeMs = responseTimeMs
                )
            )
        }
    }
}
