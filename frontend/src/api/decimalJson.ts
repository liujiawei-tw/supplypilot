import { isInteger, isLosslessNumber, LosslessNumber, parse, stringify } from 'lossless-json';

export type DecimalValue = string | number;

/** BigDecimal fields in Material, Inventory, SupplierMaterial, PO, Receiving, and StockTransaction response DTOs. */
const BIG_DECIMAL_FIELDS = new Set(['quantity', 'unitPrice', 'total', 'moq', 'packageMultiple', 'safetyStock']);

/**
 * Parse API numbers according to the formal response DTO field type.
 * BigDecimal values always remain their exact JSON token text, while Java integral
 * fields stay JavaScript numbers only when that conversion is provably safe.
 */
export function parseDecimalJson(data: unknown): unknown {
  if (typeof data !== 'string' || data.length === 0) return data;
  return parse(data, (key, value) => {
    if (!isLosslessNumber(value)) return value;
    const token = value.value;
    if (BIG_DECIMAL_FIELDS.has(key)) return token;
    if (!isInteger(token)) {
      throw new TypeError(`Unexpected non-integral token for integer field "${key}": ${token}`);
    }
    const integer = Number(token);
    if (!Number.isSafeInteger(integer)) {
      throw new RangeError(`Unsafe integer token for field "${key}": ${token}`);
    }
    return integer;
  });
}

export const decimalResponseConfig = { transformResponse: [parseDecimalJson] };

function toLosslessDecimal(value: string): LosslessNumber {
  const match = /^([+-]?)(?:(\d+)(?:\.(\d*))?|\.(\d+))(?:[eE]([+-]?\d+))?$/.exec(value.trim());
  if (!match) throw new TypeError('Invalid decimal request value');

  const sign = match[1] === '-' ? '-' : '';
  const integer = (match[2] || '0').replace(/^0+(?=\d)/, '');
  const fraction = match[3] ?? match[4] ?? '';
  const exponent = match[5] === undefined ? '' : 'e' + match[5];
  const token = sign + integer + (fraction ? '.' + fraction : '') + exponent;
  return new LosslessNumber(token);
}

/** Preserve decimal strings as JSON number tokens in the two BigDecimal create APIs. */
export function stringifyDecimalRequest(data: unknown): string | undefined {
  return stringify(data, (key, value) => {
    if (key === 'packageMultiple' && value === '') return undefined;
    if (BIG_DECIMAL_FIELDS.has(key) && typeof value === 'string') return toLosslessDecimal(value);
    return value;
  });
}

export const decimalRequestConfig = { transformRequest: [stringifyDecimalRequest] };
