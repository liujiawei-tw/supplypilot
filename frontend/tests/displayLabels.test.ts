import { describe, expect, it } from 'vitest';
import { displayLabel } from '../src/utils/displayLabels';

describe('displayLabel', () => {
  it('translates known API enum values without changing the source value', () => {
    const apiValue = 'REQUIRES_REVIEW';
    expect(displayLabel(apiValue)).toBe('需要人工審核');
    expect(apiValue).toBe('REQUIRES_REVIEW');
  });

  it('keeps unknown values readable instead of hiding backend data', () => {
    expect(displayLabel('FUTURE_STATUS')).toBe('FUTURE_STATUS');
    expect(displayLabel(null)).toBe('—');
  });
});
