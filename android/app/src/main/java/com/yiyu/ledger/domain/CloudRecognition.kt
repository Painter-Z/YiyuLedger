package com.yiyu.ledger.domain

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class RecognitionReview(val previous: CloudProposalResult, val problems: List<String>)

private fun reviewCount(result: RecognitionResult) = result.drafts.count { it.review_required }

/** One normal request and at most one correction; cancellation always cancels the operation. */
suspend fun recognizeGrounded(text: String, reference: String, custom: List<CustomCategory>,
    request: suspend (RecognitionReview?) -> CloudProposalResult): RecognitionResult {
    val proposals = request(null)
    val initial = runCatching { normalizeCloudResult(proposals, text, reference, custom) }.getOrNull()
    val missedTransaction = initial != null && initial.drafts.isEmpty() &&
        Regex("花了|花费|消费|支付|付款|买了|购买|收到|收入|到账|退款|还了|借了|赚了").containsMatchIn(text) &&
        Regex("[0-9零〇一二两三四五六七八九十百千万亿点.]+(?:元|块|角|毛|分)").containsMatchIn(text)
    if (initial != null && reviewCount(initial) == 0 && !missedTransaction) return initial
    val problems = if (missedTransaction) listOf("原文有明确交易及金额，但没有返回账单，请检查是否遗漏合并付款等真实交易")
        else initial?.drafts?.filter { it.review_required }?.map { it.issues.joinToString("；") } ?: listOf("结果结构或字段不正确")
    try {
        val corrected = normalizeCloudResult(request(RecognitionReview(proposals, problems)), text, reference, custom)
        if (initial == null || missedTransaction && corrected.drafts.isNotEmpty() && reviewCount(corrected) == 0 ||
            corrected.drafts.size == initial.drafts.size && reviewCount(corrected) < reviewCount(initial)) return corrected
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { /* Keep the first proposals if a corrective request fails. */ }
    return initial ?: error("识别结果未通过核对，请重试或手动记账")
}
