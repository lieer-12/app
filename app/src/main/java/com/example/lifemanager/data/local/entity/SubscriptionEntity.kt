package com.example.lifemanager.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import com.example.lifemanager.domain.model.BillingCycle

@Entity(tableName = "subscriptions", indices = [Index("nextBillingDate")])
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0, val appName: String, val amountMinor: Long, val currency: String,
    val billingCycle: BillingCycle, val nextBillingDate: Long, val startDate: Long, val category: String?, val note: String?,
    val isActive: Boolean, val cancelDate: Long?, val createdAt: Long, val updatedAt: Long,
)
