package com.yiyu.ledger.domain

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

@Serializable
data class RecognizedDraft(val kind: String, val title: String, val amount_minor: Long?, val category_id: String, val occurred_on: String, val time_hint: String, val note: String, val issues: List<String>,
    val source_text: String = "", val review_required: Boolean = false)
@Serializable
data class RecognitionResult(val transcript: String, val drafts: List<RecognizedDraft>, val issues: List<String>)
@Serializable
data class CloudProposal(val kind: String, val title: String, val currency: String? = null, val amount_text: String? = null, val amount_yuan: String? = null, val category_id: String? = null, val occurred_on: String? = null, val date_text: String? = null, val time_hint: String? = null, val note: String? = null,
    val source_text: String? = null, val title_text: String? = null)
@Serializable
data class CloudProposalResult(val entries: List<CloudProposal>)

private fun digit(char: Char) = "零〇一二两三四五六七八九".indexOf(char).let { listOf(0, 0, 1, 2, 2, 3, 4, 5, 6, 7, 8, 9).getOrElse(it) { error("金额表达不明确") } }
private fun unit(char: Char) = when(char) { '十' -> 10L; '百' -> 100L; '千' -> 1000L; '万' -> 10000L; '亿' -> 100000000L; else -> 0L }
private fun numberHundredths(text: String): BigDecimal {
    val numeric = Regex("^(\\d+)(?:\\.(\\d+))?([百千万亿])?$").matchEntire(text)
    if (numeric != null) {
        val scale = numeric.groupValues[3].firstOrNull()?.let { unit(it) } ?: 1L
        return (numeric.groupValues[1] + "." + numeric.groupValues[2].ifEmpty { "0" }).toBigDecimal().multiply(BigDecimal(scale * 100)).setScale(0, RoundingMode.UNNECESSARY)
    }
    val parts = text.split('点')
    require(parts.size <= 2 && Regex("^[零〇一二两三四五六七八九十百千万亿]+$").matches(parts[0]) &&
        (parts.size == 1 || Regex("^[零〇一二两三四五六七八九]{1,2}$").matches(parts[1]))) { "金额表达不明确" }
    require(!Regex("[百千万亿][一二两三四五六七八九]$").containsMatchIn(parts[0])) { "省略位数的金额需核对" }
    require(!(Regex("[十百千万亿]").containsMatchIn(parts[0]) && Regex("[一二两三四五六七八九]{2}").containsMatchIn(parts[0]))) { "金额表达不明确" }
    var total = BigInteger.ZERO
    if (!Regex("[十百千万亿]").containsMatchIn(parts[0])) total = parts[0].map { digit(it) }.joinToString("").toBigInteger()
    else {
        var section = BigInteger.ZERO
        var value = 0L
        parts[0].forEach { char ->
            val factor = unit(char)
            if (factor == 0L) value = digit(char).toLong()
            else {
                if (factor < 10000) section += BigInteger.valueOf((if (value == 0L) 1 else value) * factor)
                else { total += (section + BigInteger.valueOf(value)) * BigInteger.valueOf(factor); section = BigInteger.ZERO }
                value = 0
            }
        }
        total += section + BigInteger.valueOf(value)
    }
    val fraction = parts.getOrElse(1) { "" }.map { digit(it) }.joinToString("").padEnd(2, '0')
    return (total * BigInteger.valueOf(100) + fraction.toBigInteger()).toBigDecimal()
}
fun evidenceMinor(text: String): Long {
    require(text.isNotEmpty() && text.length <= 80) { "金额表达不明确" }
    var cleaned = text.replace(Regex("\\s|人民币|RMB|CNY|[¥￥]", RegexOption.IGNORE_CASE), "")
    cleaned = cleaned.replaceFirst(Regex("(元|块钱|块)[零〇0](?=[1-9一二两三四五六七八九]分$)"), "\$1")
    if (Regex("[角毛][0-9零〇一二两三四五六七八九]$").containsMatchIn(cleaned)) cleaned += "分"
    val fractional = Regex("^([0-9零〇一二两三四五六七八九])(?:角|毛)(?:([0-9零〇一二两三四五六七八九])分)?$").matchEntire(cleaned)
    val cents = Regex("^([0-9零〇一二两三四五六七八九])分$").matchEntire(cleaned)
    if (fractional != null || cents != null) {
        val small = if (fractional != null) numberHundredths(fractional.groupValues[1]).divide(BigDecimal.TEN) +
            fractional.groupValues[2].takeIf { it.isNotEmpty() }?.let { numberHundredths(it).divide(BigDecimal(100)) }.orZero()
            else numberHundredths(cents!!.groupValues[1]).divide(BigDecimal(100))
        return small.longValueExact().also { require(it > 0) { "金额需大于零" } }
    }
    val colloquial = Regex("^(.+?)(?:块钱|块|元)([0-9零〇一二两三四五六七八九])$").matchEntire(cleaned)
    if (colloquial != null) cleaned = colloquial.groupValues[1] + "块" + colloquial.groupValues[2] + "毛"
    val chars = "0-9零〇一二两三四五六七八九十百千万亿点."
    val fractionalChars = "0-9零〇一二两三四五六七八九"
    val match = Regex("^([$chars]+)(?:元|块钱|块)?(?:(半)|([$fractionalChars])(?:角|毛))?(?:([$fractionalChars])分)?$").matchEntire(cleaned) ?: error("金额表达不明确")
    var result = numberHundredths(match.groupValues[1])
    if (match.groupValues[2].isNotEmpty()) result += BigDecimal(50)
    if (match.groupValues[3].isNotEmpty()) result += numberHundredths(match.groupValues[3]).divide(BigDecimal.TEN)
    if (match.groupValues[4].isNotEmpty()) result += numberHundredths(match.groupValues[4]).divide(BigDecimal(100))
    val minor = result.longValueExact()
    require(minor in 1..MAX_AMOUNT) { "金额需大于零且最多两位小数" }
    return minor
}
private fun BigDecimal?.orZero() = this ?: BigDecimal.ZERO

private data class SourceClaim(val item: CloudProposal, var source: String, var start: Int, var end: Int, var valid: Boolean)

private fun tightenClaim(claim: SourceClaim) {
    val name = claim.item.title_text.orEmpty()
    val money = claim.item.amount_text.orEmpty()
    if (name.isEmpty() || money.isEmpty()) return
    var bestStart = -1; var bestEnd = claim.source.length + 1
    var nameAt = claim.source.indexOf(name); var names = 0
    while (nameAt >= 0 && names++ < 64) {
        var moneyAt = claim.source.indexOf(money); var amounts = 0
        while (moneyAt >= 0 && amounts++ < 64) {
            val start = minOf(nameAt, moneyAt)
            val end = maxOf(nameAt + name.length, moneyAt + money.length)
            if (end - start < bestEnd - bestStart) { bestStart = start; bestEnd = end }
            moneyAt = claim.source.indexOf(money, moneyAt + 1)
        }
        nameAt = claim.source.indexOf(name, nameAt + 1)
    }
    if (bestStart >= 0) {
        claim.source = claim.source.substring(bestStart, bestEnd)
        claim.start += bestStart; claim.end = claim.start + claim.source.length
    }
}

private fun sourceClaims(items: List<CloudProposal>, transcript: String): List<SourceClaim> {
    val claims = mutableListOf<SourceClaim>()
    items.forEach { item ->
        require(item.kind in KINDS && item.title.length <= 80) { "识别结果格式不正确" }
        val source = item.source_text.orEmpty().trim()
        var start = if (source.isNotEmpty() && source.length <= 2000) transcript.indexOf(source) else -1
        var available = start
        while (available >= 0 && claims.any { it.start == available && it.source == source }) available = transcript.indexOf(source, available + 1)
        if (available >= 0) start = available
        claims += SourceClaim(item, source, start, start + source.length, start >= 0)
    }
    val overlapping = BooleanArray(claims.size)
    val originalBounds = claims.map { it.start to it.end }
    for (a in claims.indices) for (b in a + 1 until claims.size) {
        if (claims[a].valid && claims[b].valid && claims[a].start < claims[b].end && claims[b].start < claims[a].end) {
            overlapping[a] = true; overlapping[b] = true
        }
    }
    claims.forEachIndexed { index, claim -> if (overlapping[index]) tightenClaim(claim) }
    claims.forEachIndexed { index, claim ->
        val previous = claims.take(index)
        if (overlapping[index] && previous.any { it.start == claim.start && it.source == claim.source }) {
            var next = transcript.indexOf(claim.source, claim.start + 1)
            while (next >= 0 && next + claim.source.length <= originalBounds[index].second) {
                if (next >= originalBounds[index].first && previous.none { it.start < next + claim.source.length && next < it.end }) {
                    claim.start = next; claim.end = next + claim.source.length; break
                }
                next = transcript.indexOf(claim.source, next + 1)
            }
        }
    }
    for (a in claims.indices) for (b in a + 1 until claims.size) {
        if (claims[a].start >= 0 && claims[b].start >= 0 && claims[a].start < claims[b].end && claims[b].start < claims[a].end) {
            claims[a].valid = false; claims[b].valid = false
        }
    }
    return claims.sortedBy { if (it.start < 0) transcript.length else it.start }
}

private fun amountPosition(transcript: String, claim: SourceClaim, evidence: String): Int {
    if (evidence.isEmpty()) return -1
    var offset = claim.source.indexOf(evidence)
    while (offset >= 0) {
        val start = claim.start + offset
        val before = transcript.getOrNull(start - 1)?.toString().orEmpty()
        val after = transcript.getOrNull(start + evidence.length)?.toString().orEmpty()
        if (!Regex("[0-9零〇一二两三四五六七八九十百千万亿点.元块角毛分负負\\-−]").containsMatchIn(before) &&
            !Regex("[0-9零〇一二两三四五六七八九十百千万亿点.角毛分]").containsMatchIn(after)) return offset
        offset = claim.source.indexOf(evidence, offset + 1)
    }
    return -1
}

private fun hasLaterCorrection(source: String, amountAt: Int, evidence: String) = Regex("(?:不对|改成|改为|更正|应该是|实际(?:是|花了))[^，。；;]{0,20}[0-9零〇一二两三四五六七八九十百千万亿点.]+(?:元|块|毛|角|分)")
    .containsMatchIn(source.substring(amountAt + evidence.length))

private fun currencyContext(transcript: String, claim: SourceClaim): String {
    if (claim.start < 0) return claim.source
    var start = claim.start; var end = claim.end
    while (start > 0 && transcript[start - 1] !in "，,。；;！？!?\n") start--
    while (end < transcript.length && transcript[end] !in "，,。；;！？!?\n") end++
    return transcript.substring(start, end)
}
fun normalizeCloudResult(raw: CloudProposalResult, transcript: String, reference: String, custom: List<CustomCategory> = emptyList()): RecognitionResult {
    require(isDateKey(reference) && raw.entries.size <= 20) { "识别结果格式不正确" }
    val drafts = sourceClaims(raw.entries, transcript).map { claim ->
        val item = claim.item
        val issues = mutableListOf<String>()
        var review = false
        var title = ""
        val titleText = item.title_text.orEmpty().trim()
        val summary = Regex("^(?:总计|合计|一共|总共|共计|找零|余额|单价)").containsMatchIn(claim.source)
        if (!claim.valid || summary) {
            review = true
            issues += if (summary) "这段原话可能是合计或找零，请核对是否需要记账" else "这笔账单的原话对应不明确，请核对名称和金额"
        } else if (titleText.isNotEmpty() && titleText.length <= 80 && claim.source.contains(titleText) &&
            !Regex("[0-9零〇一二两三四五六七八九十百千万亿点.]+(?:元|块|毛|角|分)|花了|支付了").containsMatchIn(titleText)) {
            if (item.title.isNotBlank() && titleText.contains(item.title.trim())) title = item.title.trim()
            else { title = titleText; issues += "名称已按对应原话校正，请确认" }
        } else { review = true; issues += "名称缺少这笔原话的依据，请补充名称" }
        var minor: Long? = null
        val evidence = item.amount_text ?: ""
        val foreign = item.currency != null && item.currency != "CNY" || Regex("美元|美金|港元|港币|欧元|日元|英镑|韩元|新币|澳元|外币|USD|HKD|EUR|JPY", RegexOption.IGNORE_CASE)
            .containsMatchIn(currencyContext(transcript, claim))
        val amountAt = if (claim.valid) amountPosition(transcript, claim, evidence) else -1
        if (foreign) issues += "当前仅支持人民币，外币金额请自行核对换算后填写"
        else if (claim.valid && !summary && evidence.isNotEmpty() && evidence.length <= 80 && amountAt >= 0 && !hasLaterCorrection(claim.source, amountAt, evidence)) {
            val checked = runCatching { evidenceMinor(evidence) }.getOrNull()
            if (checked != null && item.amount_yuan != null && parseMinor(item.amount_yuan) == checked) minor = checked
            else { review = true; issues += "金额待核对，请手动填写" }
        } else {
            if (evidence.isNotEmpty()) review = true
            issues += if (evidence.isNotEmpty()) "金额与这笔原话不一致，请核对填写" else "没有明确的金额，请手动填写"
        }
        val category = item.category_id?.takeIf { id -> categoriesForKind(item.kind, custom).any { it.id == id } }
            ?: when(item.kind) { "transfer" -> "transfer"; "debt" -> "debt"; else -> "other" }
        if (category != item.category_id) issues += "分类已调整，请确认"
        var occurred = reference
        val dateText = item.date_text ?: ""
        if (dateText.isNotEmpty() && transcript.contains(dateText)) {
            val relative = listOf("前天", "昨天", "今天", "明天", "后天").indexOf(dateText)
            if (relative >= 0) occurred = shiftDays(reference, (relative - 2).toLong())
            else if (item.occurred_on != null && isDateKey(item.occurred_on)) { occurred = item.occurred_on; issues += "日期根据原文推算，请确认" }
            else issues += "日期未能确定，暂用录入日期"
        } else if (!item.occurred_on.isNullOrEmpty() && item.occurred_on != reference) issues += "日期缺少原文依据，暂用录入日期"
        if (!isDateKey(occurred)) { occurred = reference; issues += "日期超出支持范围，请确认" }
        RecognizedDraft(item.kind, title, minor, category, occurred, item.time_hint?.takeIf { transcript.contains(it) }?.take(40) ?: "", item.note?.take(500) ?: "", issues,
            source_text = if (claim.valid && !summary) claim.source else "", review_required = review)
    }
    return RecognitionResult(transcript, drafts, if (drafts.isEmpty()) listOf("没有识别到可记账内容，请修改描述或手动记账") else emptyList())
}
class SpeechAccumulator {
    private val sentences = sortedMapOf<Long, String>()
    var preview = ""; private set
    fun accept(begin: Long, text: String, final: Boolean): String {
        if (final) sentences[begin] = text
        val ordered = sentences.values.joinToString("")
        preview = if (final) ordered else ordered + text
        return preview
    }
    fun finish() = sentences.values.joinToString("").ifEmpty { preview }
}
