import { describe, expect, it } from 'vitest';
import { displayLabel } from '../src/utils/displayLabels';

describe('displayLabel', () => {
  it('translates known API enum values without changing the source value', () => {
    const apiValue = 'REQUIRES_REVIEW';
    expect(displayLabel(apiValue)).toBe('需要人工審核');
    expect(apiValue).toBe('REQUIRES_REVIEW');
  });

  it('translates the new supplier document types and persistent publication states', () => {
    expect(displayLabel('SUPPLIER_PROFILE')).toBe('供應商檔案');
    expect(displayLabel('SUPPLIER_COOPERATION_RECORD')).toBe('合作往來紀錄');
    expect(displayLabel('SUPPLIER_EVALUATION')).toBe('供應商評估');
    expect(displayLabel('INDEXED')).toBe('已發布');
    expect(displayLabel('UNAVAILABLE')).toBe('索引服務暫不可用');
  });

  it('translates purchase-order stages, AI source types, tools, and answer sources for display', () => {
    expect(displayLabel('NEW_REQUEST')).toBe('新請購');
    expect(displayLabel('APPROVED_PENDING_ORDER')).toBe('已核准待下單');
    expect(displayLabel('PAYMENT_RECONCILIATION')).toBe('請款核銷');
    expect(displayLabel('PURCHASE_ORDER')).toBe('採購單');
    expect(displayLabel('INVENTORY')).toBe('庫存');
    expect(displayLabel('SUPPLIER_RECOMMENDATION_RESULT')).toBe('供應商評估結果');
    expect(displayLabel('SEARCH_DOCUMENTS')).toBe('搜尋文件');
    expect(displayLabel('BACKEND_FACTS')).toBe('後端查核資料');
  });

  it('keeps unknown values readable instead of hiding backend data', () => {
    expect(displayLabel('FUTURE_STATUS')).toBe('FUTURE_STATUS');
    expect(displayLabel(null)).toBe('—');
  });
});
