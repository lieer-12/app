package com.example.lifemanager.domain.model

import java.time.Instant

data class Tag(
    val id: Long = 0,
    val name: String,
    val color: Int,
    val createdAt: Instant = Instant.now(),
)
