const adjustmentQuantityPattern = /^-?\d{1,15}(\.\d{1,4})?$/;

export function getAdjustmentQuantityError(value: string | undefined): string | undefined {
  if (value === undefined || value === '') return undefined;
  if (!adjustmentQuantityPattern.test(value)) return 'Use at most 15 integer digits and 4 decimal places';
  if (/^-?0+(\.0{1,4})?$/.test(value)) return 'Quantity must not be zero';
  return undefined;
}

export function getAdjustmentReasonError(value: string | undefined): string | undefined {
  if (!value?.trim()) return 'Reason is required';
  if (value.length > 500) return 'Reason must be 500 characters or fewer';
  return undefined;
}
