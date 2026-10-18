package com.yiyu.ledger.backup

import com.yiyu.ledger.domain.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

val ledgerJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
private val backupJson = Json(ledgerJson) { prettyPrint = true }
@Serializable
data class BackupDocument(val format: String, val version: Int, val exportedAt: String = "", val entries: List<LedgerEntry>, val categories: List<CustomCategory> = emptyList(), val monthlyBudgets: List<MonthlyBudget> = emptyList())

fun parseBackup(content: String): BackupDocument {
    require(content.length <= 5 * 1024 * 1024) { "备份文件过大" }
    val root = ledgerJson.parseToJsonElement(content).jsonObject
    require(root["format"]?.jsonPrimitive?.let { it.isString && it.content == "yiyu-ledger" } == true &&
        root["version"]?.jsonPrimitive?.let { !it.isString && it.intOrNull in listOf(1, 2, 3) } == true) { "不支持此备份格式" }
    val version = root.getValue("version").jsonPrimitive.int
    val categories = if (version == 1) emptyList() else ledgerJson.decodeFromJsonElement<List<CustomCategory>>(root["categories"] ?: error("缺少分类数据"))
    requireValid(validateCategoryCatalog(categories))
    val budgets = if (version < 3) emptyList() else {
        val rawBudgets = root["monthlyBudgets"] as? JsonArray ?: error("缺少月预算数据")
        require(rawBudgets.size <= 3600) { "月预算数量不正确" }
        rawBudgets.forEach { element ->
            val item = element.jsonObject
            require((item["month"] as? JsonPrimitive)?.isString == true &&
                (item["amountMinor"] as? JsonPrimitive)?.let { !it.isString && it.longOrNull != null } == true) { "预算数据格式不正确" }
        }
        ledgerJson.decodeFromJsonElement<List<MonthlyBudget>>(rawBudgets)
    }
    requireValid(validateMonthlyBudgets(budgets))
    val rawEntries = root["entries"] as? JsonArray ?: error("缺少账目数据")
    require(rawEntries.size <= 20_000) { "备份最多支持2万笔账目" }
    rawEntries.forEach { element ->
        val item = element.jsonObject
        listOf("amountMinor", "createdAt", "updatedAt", "deletedAt").forEach { name ->
            require((item[name] as? JsonPrimitive)?.let { !it.isString && it.longOrNull != null } == true) { "账目数字格式不正确" }
        }
    }
    val entries = ledgerJson.decodeFromJsonElement<List<LedgerEntry>>(rawEntries)
    require(entries.size <= 20_000) { "备份最多支持2万笔账目" }
    val ids = mutableSetOf<String>()
    entries.forEach { requireValid(validateEntry(it, categories)); require(ids.add(it.id)) { "备份含重复账目编号" } }
    return BackupDocument("yiyu-ledger", version, root["exportedAt"]?.jsonPrimitive?.contentOrNull ?: "", entries.filter { it.deletedAt == 0L }, categories, budgets)
}
fun exportBackup(entries: List<LedgerEntry>, custom: List<CustomCategory>, exportedAt: String = Instant.now().toString(), monthlyBudgets: List<MonthlyBudget> = emptyList()): String =
    backupJson.encodeToString(BackupDocument.serializer(), BackupDocument("yiyu-ledger", 3, exportedAt, entries.filter { it.deletedAt == 0L }, custom, monthlyBudgets))

private fun csvCell(value: String): String {
    val text = if (value.firstOrNull() in listOf('=', '+', '@', '-', '\t', '\r')) "'$value" else value
    return "\"" + text.replace("\"", "\"\"") + "\""
}
fun exportCsv(entries: List<LedgerEntry>, custom: List<CustomCategory>): String = "\uFEFF" +
    (listOf("日期,类型,分类,名称,金额（元）,币种,备注") + filterEntries(entries).map {
        listOf(it.occurredOn, kindName(it.kind), categoryById(it.categoryId, custom).name, it.title, decimalAmount(it.amountMinor), it.currency, it.note).joinToString(",") { cell -> csvCell(cell) }
    }).joinToString("\r\n")
