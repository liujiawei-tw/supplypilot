export type DecimalDisplayValue = string | number | null | undefined;

const DATE_ONLY_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;
const DECIMAL_PATTERN = /^([+-]?)(\d+)(?:\.(\d+))?$/;

function isValidDateParts(year: number, month: number, day: number): boolean {
  if (month < 1 || month > 12 || day < 1) return false;
  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return day <= daysInMonth[month - 1];
}

function expandExponent(value: string): string {
  const match = /^([+-]?)(\d+)(?:\.(\d+))?[eE]([+-]?\d+)$/.exec(value);
  if (!match) return value;

  const [, sign, whole, fraction = '', rawExponent] = match;
  const exponent = Number(rawExponent);
  if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > 100) return value;

  const digits = whole + fraction;
  const decimalIndex = whole.length + exponent;
  if (decimalIndex <= 0) return `${sign}0.${'0'.repeat(-decimalIndex)}${digits}`;
  if (decimalIndex >= digits.length) return `${sign}${digits}${'0'.repeat(decimalIndex - digits.length)}`;
  return `${sign}${digits.slice(0, decimalIndex)}.${digits.slice(decimalIndex)}`;
}

/** Groups a decimal's integer digits without rounding or changing its fractional text. */
export function formatDecimal(value: DecimalDisplayValue, emptyValue = '—'): string {
  if (value === null || value === undefined) return emptyValue;
  const source = expandExponent(String(value).trim());
  if (!source) return emptyValue;

  const match = DECIMAL_PATTERN.exec(source);
  if (!match) return source;

  const [, sign, integer, fraction] = match;
  const groupedInteger = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${sign}${groupedInteger}${fraction === undefined ? '' : `.${fraction}`}`;
}

export function formatQuantity(value: DecimalDisplayValue, unit: string | null | undefined): string {
  const formatted = formatDecimal(value);
  return formatted === '—' ? formatted : unit ? `${formatted} ${unit}` : formatted;
}

/** Shows the API-provided currency code and keeps all supplied decimal places. */
export function formatCurrency(value: DecimalDisplayValue, currency: string | null | undefined): string {
  const formatted = formatDecimal(value);
  if (formatted === '—') return formatted;
  return currency ? `${currency} ${formatted}` : formatted;
}

/** AI answers follow the V2 compact money rule: round for display to two places and trim trailing zeroes. */
export function formatAiCurrency(value: DecimalDisplayValue, currency: string | null | undefined): string {
  if (value === null || value === undefined) return '—';
  const source = expandExponent(String(value).trim());
  const match = DECIMAL_PATTERN.exec(source);
  if (!match) return currency ? `${currency} ${source}` : source;
  const [, sign, integer, fraction = ''] = match;
  let whole = BigInt(integer);
  let cents = BigInt(fraction.slice(0, 2).padEnd(2, '0') || '0');
  if ((fraction[2] ?? '0') >= '5') cents += 1n;
  if (cents >= 100n) { whole += 1n; cents = 0n; }
  const fractionText = cents.toString().padStart(2, '0').replace(/0+$/u, '');
  const rounded = `${sign && (whole !== 0n || cents !== 0n) ? sign : ''}${whole.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ',')}${fractionText ? `.${fractionText}` : ''}`;
  return currency === 'TWD' ? `NT$${rounded}` : currency ? `${currency} ${rounded}` : rounded;
}

export function formatDate(value: string | null | undefined, emptyValue = '—'): string {
  if (!value) return emptyValue;

  const dateOnly = DATE_ONLY_PATTERN.exec(value);
  if (dateOnly) {
    const [, year, month, day] = dateOnly;
    if (!isValidDateParts(Number(year), Number(month), Number(day))) return value;
    return `${year}/${month}/${day}`;
  }

  const formatted = formatDateTime(value);
  return formatted === value ? value : formatted.slice(0, 10);
}

export function formatDateTime(value: string | null | undefined, emptyValue = '—'): string {
  if (!value) return emptyValue;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;

  const parts = new Intl.DateTimeFormat('zh-TW', {
    calendar: 'gregory',
    day: '2-digit',
    hour: '2-digit',
    hourCycle: 'h23',
    minute: '2-digit',
    month: '2-digit',
    numberingSystem: 'latn',
    timeZone: 'Asia/Taipei',
    year: 'numeric',
  }).formatToParts(date);
  const byType = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${byType.year}/${byType.month}/${byType.day} ${byType.hour}:${byType.minute}`;
}
