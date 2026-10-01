package com.example.lifemanager.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "subscription_reminders", primaryKeys = ["subscriptionId", "daysBefore"], foreignKeys = [ForeignKey(entity = SubscriptionEntity::class, parentColumns = ["id"], childColumns = ["subscriptionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("subscriptionId")])
data class SubscriptionReminderEntity(val subscriptionId: Long, val daysBefore: Int)
