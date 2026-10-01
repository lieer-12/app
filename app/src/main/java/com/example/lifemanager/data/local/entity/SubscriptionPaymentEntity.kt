package com.example.lifemanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "subscription_payments", foreignKeys = [ForeignKey(entity = SubscriptionEntity::class, parentColumns = ["id"], childColumns = ["subscriptionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("subscriptionId")])
data class SubscriptionPaymentEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val subscriptionId: Long, val amountMinor: Long, val currency: String, val paidAt: Long, val note: String?)
