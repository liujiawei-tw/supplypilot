const adjustmentQuantityPattern = /^-?\d{1,15}(\.\d{1,4})?$/;

export function getAdjustmentQuantityError(value: string | undefined): string | undefined {
  if (value === undefined || value === '') return undefined;
  if (!adjustmentQuantityPattern.test(value)) return '最多 15 位整數與 4 位小數';
  if (/^-?0+(\.0{1,4})?$/.test(value)) return '數量不可為 0';
  return undefined;
}

export function getAdjustmentReasonError(value: string | undefined): string | undefined {
  if (!value?.trim()) return '請輸入調整原因';
  if (value.length > 500) return '調整原因不可超過 500 個字元';
  return undefined;
}
