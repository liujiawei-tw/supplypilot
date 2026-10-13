import { parse } from 'lossless-json';

/** Keep JSON decimal/exponent tokens as their exact source text; IDs and page counters remain numbers. */
export function parseDecimalJson(data: unknown): unknown {
  if (typeof data !== 'string' || data.length === 0) return data;
  return parse(data, undefined, {
    parseNumber: (value) => value.includes('.') || /e/i.test(value) ? value : Number(value),
  });
}

export const decimalResponseConfig = { transformResponse: [parseDecimalJson] };
