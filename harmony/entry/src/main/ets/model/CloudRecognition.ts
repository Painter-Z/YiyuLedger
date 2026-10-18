import type { CustomCategory } from './Ledger';
import type { CloudProposalResult, RecognitionResult } from './CloudParsing';
import { normalizeCloudResult } from './CloudParsing';

export interface RecognitionReview { previous: CloudProposalResult; problems: string[]; }

function reviewCount(result: RecognitionResult): number {
  return result.drafts.filter((draft) => draft.review_required === true).length;
}

// One normal request; at most one corrective request. A failed review preserves the initial proposals.
export async function recognizeGrounded(text: string, reference: string, custom: CustomCategory[],
  request: (review?: RecognitionReview) => Promise<CloudProposalResult>): Promise<RecognitionResult> {
  const proposals: CloudProposalResult = await request();
  let initial: RecognitionResult | undefined;
  try { initial = normalizeCloudResult(proposals, text, reference, custom); } catch (error) {}
  const missedTransaction: boolean = initial !== undefined && initial.drafts.length === 0 &&
    /花了|花费|消费|支付|付款|买了|购买|收到|收入|到账|退款|还了|借了|赚了/.test(text) &&
    /[0-9零〇一二两三四五六七八九十百千万亿点.]+(?:元|块|角|毛|分)/.test(text);
  if (initial && reviewCount(initial) === 0 && !missedTransaction) return initial;
  const problems: string[] = missedTransaction ? ['原文有明确交易及金额，但没有返回账单，请检查是否遗漏合并付款等真实交易'] : initial ? initial.drafts.filter((draft) => draft.review_required === true)
    .map((draft) => draft.issues.join('；')) : ['结果结构或字段不正确'];
  try {
    const corrected: RecognitionResult = normalizeCloudResult(await request({ previous: proposals, problems: problems }), text, reference, custom);
    if (!initial || missedTransaction && corrected.drafts.length > 0 && reviewCount(corrected) === 0 ||
      corrected.drafts.length === initial.drafts.length && reviewCount(corrected) < reviewCount(initial)) return corrected;
  } catch (error) {}
  if (initial) return initial;
  throw new Error('识别结果未通过核对，请重试或手动记账');
}
