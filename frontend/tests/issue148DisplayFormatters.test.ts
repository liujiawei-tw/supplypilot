import { describe, expect, it } from 'vitest';
import { formatCurrency, formatDate, formatDateTime, formatDecimal, formatQuantity } from '../src/utils/displayFormatters';

describe('Issue #148 shared display formatters', () => {
  it('groups integer digits without rounding or trimming decimal places', () => {
    expect(formatDecimal('12345678901234567890.01002000')).toBe('12,345,678,901,234,567,890.01002000');
    expect(formatDecimal('-1000000.0000')).toBe('-1,000,000.0000');
    expect(formatDecimal(1234567.5)).toBe('1,234,567.5');
  });

  it('keeps quantities and the supplied currency code beside the exact decimal text', () => {
    expect(formatQuantity('1234567890.2500', 'KG')).toBe('1,234,567,890.2500 KG');
    expect(formatCurrency('1234567890.2500', 'USD')).toBe('USD 1,234,567,890.2500');
    expect(formatCurrency(null, 'TWD')).toBe('—');
  });

  it('formats date-only values without timezone shifting and validates calendar boundaries', () => {
    expect(formatDate('2024-02-29')).toBe('2024/02/29');
    expect(formatDate('2025-02-29')).toBe('2025-02-29');
    expect(formatDate('2024-12-31')).toBe('2024/12/31');
    expect(formatDate(null, '未設定')).toBe('未設定');
  });

  it('renders timestamps in Taiwan time with a stable Gregorian format', () => {
    expect(formatDateTime('2024-12-31T18:05:00Z')).toBe('2025/01/01 02:05');
    expect(formatDate('2024-12-31T18:05:00Z')).toBe('2025/01/01');
    expect(formatDateTime('invalid timestamp')).toBe('invalid timestamp');
  });
});
