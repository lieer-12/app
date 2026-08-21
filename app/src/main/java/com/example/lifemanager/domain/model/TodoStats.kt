package com.example.lifemanager.domain.model

data class TodoStats(
    val completedCount: Int,
    val pendingCount: Int,
    val completionRate: Double,
)
