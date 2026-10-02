package com.example.lifemanager.ui.subscription

import com.example.lifemanager.domain.model.BillingCycle
import java.math.BigDecimal
import java.util.Currency

fun amountText(minor: Long, currency: String = "CNY"): String {
    val digits = runCatching { Currency.getInstance(currency).defaultFractionDigits }.getOrDefault(2).coerceAtLeast(0)
    return BigDecimal.valueOf(minor, digits).toPlainString()
}

fun moneyText(minor: Long, currency: String = "CNY") = "$currency ${amountText(minor, currency)}"

fun cycleLabel(cycle: BillingCycle): String = when (cycle) {
    BillingCycle.WEEKLY -> "周付"
    BillingCycle.MONTHLY -> "月付"
    BillingCycle.QUARTERLY -> "季付"
    BillingCycle.YEARLY -> "年付"
}
