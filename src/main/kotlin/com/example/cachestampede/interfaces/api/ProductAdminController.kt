package com.example.cachestampede.interfaces.api

import com.example.cachestampede.application.product.ProductDto
import com.example.cachestampede.application.product.ProductService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

@RestController
@RequestMapping("/api/v1/admin/products")
class ProductAdminController(
    private val productService: ProductService
) {
    /**
     * 상품 생성 (테스트 데이터 생성용)
     */
    @PostMapping
    fun createProduct(@Valid @RequestBody request: CreateProductRequest): ResponseEntity<ProductDto> {
        val product = productService.create(
            name = request.name,
            description = request.description,
            price = request.price,
            stockQuantity = request.stockQuantity,
            category = request.category
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(product)
    }

    /**
     * 대량 상품 생성 (로드 테스트용)
     */
    @PostMapping("/bulk")
    fun createBulkProducts(@RequestParam count: Int = 10): ResponseEntity<Map<String, Any>> {
        val products = (1..count).map { i ->
            productService.create(
                name = "Test Product $i",
                description = "Description for test product $i",
                price = BigDecimal.valueOf(1000L + (i * 100)),
                stockQuantity = 100 + i,
                category = listOf("Electronics", "Books", "Clothing", "Food")[i % 4]
            )
        }
        return ResponseEntity.ok(mapOf(
            "created" to products.size,
            "productIds" to products.map { it.id }
        ))
    }
}

data class CreateProductRequest(
    @field:NotBlank
    val name: String,
    val description: String? = null,
    @field:Positive
    val price: BigDecimal,
    @field:Positive
    val stockQuantity: Int,
    @field:NotBlank
    val category: String
)
