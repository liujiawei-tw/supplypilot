const labels: Record<string, string> = {
  ACTIVE: '啟用', INACTIVE: '停用', CANCELLED: '已取消',
  DRAFT: '草稿', APPROVED: '已核准', REJECTED: '已駁回', ORDERED: '已下單', RECEIVED: '已收貨',
  FOLLOW_UP: '下單跟催中', RECEIVING_INSPECTION: '到貨驗收中', LEGACY_UNKNOWN: '舊資料階段待確認',
  NEW_REQUEST: '新請購', SOURCING: '詢價／尋源', APPROVAL: '待審核',
  APPROVED_PENDING_ORDER: '已核准待下單', ORDER_FOLLOW_UP: '下單與交期跟催',
  PAYMENT_RECONCILIATION: '請款核銷', COMPLETED: '已完成',
  PROCESSING: '處理中', READY: '已就緒', FAILED: '處理失敗',
  INDEXED: '已發布', UNAVAILABLE: '索引服務暫不可用', NOT_READY: '文件尚未就緒',
  SOURCE_MISSING: '缺少來源分塊', NOT_REVIEWED: '尚未審核', EXCLUDED: '已排除出站',
  REVIEW_REQUIRED: '需要人工審核', STALE_APPROVAL: '審核已失效', POLICY_RECHECK_REQUIRED: '需要重新檢查出站政策',
  QUEUED: '排隊中', RETRY_WAIT: '等待重試', SUCCEEDED: '索引工作完成',
  REQUIRES_REVIEW: '需要人工審核', SKIPPED: '已略過', PENDING: '待處理',
  REQUIRES_ADMIN_REVIEW: '需要管理員審核',
  VALIDATED: '驗證通過', VALIDATION_FAILED: '驗證失敗', IMPORTED: '已匯入',
  ANSWERED: '已回答', NOT_FOUND: '查無資料', INSUFFICIENT_EVIDENCE: '證據不足', NEEDS_CLARIFICATION: '需要補充資訊',
  PURCHASE_NOT_REQUIRED: '無須採購', SUPPLIER_RECOMMENDED: '建議供應商', NO_QUALIFIED_SUPPLIER: '沒有合格供應商',
  OVERDUE: '已逾期', PURCHASE_RECEIVE: '採購收貨', MANUAL_ADJUSTMENT: '手動調整',
  SUPPLIER_CONTRACT: '供應商合約', QUOTATION: '報價單', PROCUREMENT_SOP: '採購作業程序',
  PRODUCT_SPECIFICATION: '產品規格', QUALITY_REQUIREMENT: '品質要求',
  SUPPLIER_PROFILE: '供應商檔案', SUPPLIER_COOPERATION_RECORD: '合作往來紀錄', SUPPLIER_EVALUATION: '供應商評估',
  DOCUMENT: '文件', PURCHASE_ORDER: '採購單', INVENTORY: '庫存', RECOMMENDATION: '採購建議',
  SUPPLIER_RECOMMENDATION_RESULT: '供應商評估結果',
  PURCHASE_RECOMMENDATION: '採購建議',
  QUERY_RECOMMENDATION: '查詢採購建議', EXPLAIN_RECOMMENDATION: '說明採購建議',
  QUERY_SUPPLIER_RANKING: '查詢供應商評估', QUERY_INVENTORY: '查詢庫存',
  QUERY_SUPPLIER_QUOTES: '查詢有效報價', QUERY_AVERAGE_PRICE: '計算平均報價',
  QUERY_SUPPLIER: '查詢供應商主檔', QUERY_PURCHASE_ORDER: '查詢採購單',
  QUERY_INVENTORY_BATCH: '批次查詢庫存', QUERY_INVENTORY_BY_NAME: '依物料名稱查詢庫存',
  QUERY_OPEN_PO: '查詢待收貨採購單', SEARCH_DOCUMENTS: '搜尋文件',
  SUPPLIER: '供應商', SUPPLIER_MATERIAL: '供應商物料報價',
  BACKEND_FACTS: '後端查核資料', MODEL_EXPLANATION: 'AI 說明',
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
