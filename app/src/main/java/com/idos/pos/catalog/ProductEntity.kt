package com.idos.pos.catalog

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal

/**
 * Sellable product catalog row (task 4.1; specs/product-catalog/spec.md).
 * No store scoping (single implicit store, per design.md).
 *
 * - `code` is required and globally unique.
 * - `barcode` is optional; when present it must be globally unique. SQLite's
 *   unique index treats every `NULL` as distinct, so any number of products
 *   may have an absent barcode without violating the constraint (see
 *   specs/product-catalog/spec.md "Barcode Uniqueness Is Optional-If-Present").
 *   Blank/whitespace-only input is normalized to `null` by
 *   [CatalogRepository] before it ever reaches this entity.
 * - `unitMeasureId` is RESTRICT (a unit of measure referenced by a product
 *   cannot be deleted).
 * - `categoryId` is nullable + SET NULL (deleting a category detaches its
 *   products rather than blocking the delete or cascading).
 */
@Entity(
    tableName = "product",
    foreignKeys = [
        ForeignKey(
            entity = com.idos.pos.catalog.UnitMeasureEntity::class,
            parentColumns = ["id"],
            childColumns = ["unitMeasureId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = com.idos.pos.catalog.CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["code"], unique = true),
        Index(value = ["barcode"], unique = true),
        Index(value = ["categoryId"]),
        Index(value = ["unitMeasureId"]),
    ],
)
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val code: String,
    val barcode: String? = null,
    val price: BigDecimal,
    val costPrice: BigDecimal,
    val unitMeasureId: Long,
    val categoryId: Long? = null,
)
