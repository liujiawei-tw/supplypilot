const DECIMAL_PATTERN = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/;

export function isNonNegativeDecimal(value: unknown): boolean {
  if (typeof value !== 'string' && typeof value !== 'number') return false;
  const source = String(value).trim();
  if (!DECIMAL_PATTERN.test(source)) return false;
  const mantissa = source.split(/[eE]/, 1)[0];
  const digits = mantissa.replace(/[+\-.]/g, '');
  return /^0*$/.test(digits) || !mantissa.startsWith('-');
}

export function isPositiveDecimal(value: unknown): boolean {
  if (typeof value !== 'string' && typeof value !== 'number') return false;
  const source = String(value).trim();
  if (!DECIMAL_PATTERN.test(source)) return false;
  const mantissa = source.split(/[eE]/, 1)[0];
  const digits = mantissa.replace(/[+\-.]/g, '');
  return !/^0*$/.test(digits) && !mantissa.startsWith('-');
}

function decimalValidator(message: string, valid: (value: unknown) => boolean) {
  return {
    validator: (_rule: unknown, value: unknown): Promise<void> => {
      if (value === undefined || value === null || value === '') return Promise.resolve();
      return valid(value) ? Promise.resolve() : Promise.reject(new Error(message));
    },
  };
}

export const nonNegativeDecimalRule = (message: string) => decimalValidator(message, isNonNegativeDecimal);
export const positiveDecimalRule = (message: string) => decimalValidator(message, isPositiveDecimal);
