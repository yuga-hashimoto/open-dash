package com.opendash.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Durable FAQ / knowledge base row.
 * Tags are stored as JSON at the store boundary.
 */
@Entity(tableName = "knowledge")
data class KnowledgeEntity(
    @PrimaryKey val id: String,
    val question: String,
    val answer: String,
    val tagsJson: String,
    val createdAtMs: Long
)
