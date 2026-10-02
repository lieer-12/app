package com.example.lifemanager.ui.subscription

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.lifemanager.domain.model.SubscriptionStats

/** Coordinates use Float; financial data and textual values stay in exact minor units. */
@Composable
internal fun SubscriptionCharts(stats: SubscriptionStats) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val colors = listOf(primary, secondary, MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
    ChartCard("最近 6 个月（CNY）") {
        Text("预计 / 实际，两者独立显示")
        val description = stats.months.joinToString("；") { "${it.month} 预计 ${moneyText(it.forecastMinor)}，实际 ${moneyText(it.actualMinor)}" }
        Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
            val max = stats.months.maxOfOrNull { maxOf(it.forecastMinor, it.actualMinor) }?.coerceAtLeast(1) ?: 1
            val cell = size.width / stats.months.size.coerceAtLeast(1)
            val barWidth = cell * 0.25f
            stats.months.forEachIndexed { index, month ->
                listOf(month.forecastMinor to primary, month.actualMinor to secondary).forEachIndexed { series, (amount, color) ->
                    val height = size.height * (amount.toFloat() / max.toFloat())
                    drawRect(color, Offset(cell * index + cell * 0.15f + series * barWidth * 1.2f, size.height - height), Size(barWidth, height))
                }
            }
        }
        stats.months.forEach { month ->
            Text("${month.month}　预计 ${amountText(month.forecastMinor)} / 实际 ${amountText(month.actualMinor)}")
        }
    }
    ChartCard("本月分类占比（CNY 预计）") {
        if (stats.categoryStats.isEmpty()) {
            Text("本月暂无人民币预计费用")
        } else {
            val total = stats.currentMonth.forecastMinor.coerceAtLeast(1)
            val description = stats.categoryStats.joinToString("；") { "${it.category} ${moneyText(it.amountMinor)}" }
            Canvas(Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = description }) {
                val diameter = minOf(size.width, size.height)
                var start = -90f
                stats.categoryStats.forEachIndexed { index, item ->
                    val sweep = 360f * item.amountMinor.toFloat() / total.toFloat()
                    drawArc(colors[index % colors.size], start, sweep, true,
                        Offset((size.width - diameter) / 2, 0f), Size(diameter, diameter))
                    start += sweep
                }
            }
            stats.categoryStats.forEach { Text("${it.category}：${moneyText(it.amountMinor)}") }
        }
    }
    ChartCard("计费周期费用对比（本月 CNY 预计）") {
        if (stats.billingCycleStats.isEmpty()) Text("本月暂无费用")
        val max = stats.billingCycleStats.maxOfOrNull { it.amountMinor }?.coerceAtLeast(1) ?: 1
        stats.billingCycleStats.forEach { item ->
            val label = "${cycleLabel(item.billingCycle)}：${moneyText(item.amountMinor)}"
            Text(label)
            Canvas(Modifier.fillMaxWidth().height(14.dp).semantics { contentDescription = label }) {
                drawRect(primary, size = Size(size.width * item.amountMinor.toFloat() / max.toFloat(), size.height))
            }
        }
    }
}

@Composable
private fun ChartCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
