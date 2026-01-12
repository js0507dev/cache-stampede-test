package com.example.cachestampede.application.product

import com.example.cachestampede.domain.product.Product
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant

data class ProductDto(
    val id: Long,
    val name: String,
    val description: String?,
    val price: BigDecimal,
    val stockQuantity: Int,
    val category: String,
    val createdAt: Instant,
    val updatedAt: Instant
) : Serializable {
    companion object {
        fun from(product: Product): ProductDto = ProductDto(
            id = product.id,
            name = product.name,
            description = product.description,
            price = product.price,
            stockQuantity = product.stockQuantity,
            category = product.category,
            createdAt = product.createdAt,
            updatedAt = product.updatedAt
        )
    }
}
