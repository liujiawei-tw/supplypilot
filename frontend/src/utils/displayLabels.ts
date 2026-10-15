const labels: Record<string, string> = {
  ACTIVE: '啟用', INACTIVE: '停用', CANCELLED: '已取消',
  DRAFT: '草稿', APPROVED: '已核准', REJECTED: '已駁回', ORDERED: '已下單', RECEIVED: '已收貨',
  PROCESSING: '處理中', READY: '已就緒', FAILED: '處理失敗',
  REQUIRES_REVIEW: '需要人工審核', SKIPPED: '已略過', PENDING: '待處理',
  VALIDATED: '驗證通過', VALIDATION_FAILED: '驗證失敗', IMPORTED: '已匯入',
  ANSWERED: '已回答', INSUFFICIENT_EVIDENCE: '證據不足', NEEDS_CLARIFICATION: '需要補充資訊',
  PURCHASE_NOT_REQUIRED: '無須採購', SUPPLIER_RECOMMENDED: '建議供應商', NO_QUALIFIED_SUPPLIER: '沒有合格供應商',
  OVERDUE: '已逾期', PURCHASE_RECEIVE: '採購收貨', MANUAL_ADJUSTMENT: '手動調整',
  SUPPLIER_CONTRACT: '供應商合約', QUOTATION: '報價單', PROCUREMENT_SOP: '採購作業程序',
  PRODUCT_SPECIFICATION: '產品規格', QUALITY_REQUIREMENT: '品質要求',
  SUPPLIER_INACTIVE: '供應商目前停用',
  SUPPLIER_MATERIAL_NOT_FOUND: '找不到供應商與物料的供應關係',
  SUPPLIER_MATERIAL_INACTIVE: '供應商物料關係目前停用',
  DELIVERY_DATE_NOT_FEASIBLE: '交期無法滿足需求日期',
};

/** Display-only translation. Keep the original value for API requests and domain logic. */
export function displayLabel(value: string | null | undefined): string {
  if (!value) return '—';
  return labels[value] ?? value;
}
