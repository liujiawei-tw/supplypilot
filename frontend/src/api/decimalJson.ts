import { isInteger, isLosslessNumber, parse } from 'lossless-json';

export type DecimalValue = string | number;

/** BigDecimal fields in Inventory, SupplierMaterial, PO, Receiving, and StockTransaction response DTOs. */
const BIG_DECIMAL_FIELDS = new Set(['quantity', 'unitPrice', 'total', 'moq', 'packageMultiple']);

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
