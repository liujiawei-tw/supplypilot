export interface LocalCsvRow {
  key: number;
  line: number;
  values: string[];
}

export interface LocalCsvPreview {
  headers: string[];
  rows: LocalCsvRow[];
}

interface ParsedRecord {
  line: number;
  values: string[];
}

export function parseCsvPreview(source: string): LocalCsvPreview {
  const text = source.startsWith('\uFEFF') ? source.slice(1) : source;
  const records: ParsedRecord[] = [];
  let values: string[] = [];
  let field = '';
  let inQuotes = false;
  let afterClosingQuote = false;
  let line = 1;
  let recordLine = 1;
  let recordTouched = false;

  const finishRecord = () => {
    values.push(field);
    records.push({ line: recordLine, values });
    values = [];
    field = '';
    recordTouched = false;
    afterClosingQuote = false;
  };

  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (inQuotes) {
      if (character === '"') {
        if (text[index + 1] === '"') {
          field += '"';
          index += 1;
        } else {
          inQuotes = false;
          afterClosingQuote = true;
        }
      } else {
        field += character;
        if (character === '\n') line += 1;
        else if (character === '\r' && text[index + 1] !== '\n') line += 1;
      }
      continue;
    }

    if (character === '"' && field.length === 0 && !afterClosingQuote) {
      inQuotes = true;
      recordTouched = true;
    } else if (character === ',') {
      values.push(field);
      field = '';
      afterClosingQuote = false;
      recordTouched = true;
    } else if (character === '\r' || character === '\n') {
      finishRecord();
      if (character === '\r' && text[index + 1] === '\n') index += 1;
      line += 1;
      recordLine = line;
    } else {
      if (afterClosingQuote && character !== ' ' && character !== '\t') {
        throw new Error(`第 ${line} 行的引號後有無效字元`);
      }
      field += character;
      recordTouched = true;
    }
  }

  if (inQuotes) throw new Error(`第 ${recordLine} 行有未關閉的引號`);
  if (recordTouched || values.length > 0 || field.length > 0) finishRecord();
  if (records.length === 0) return { headers: [], rows: [] };

  const [header, ...data] = records;
  const columnCount = Math.max(header.values.length, ...data.map((record) => record.values.length));
  const headers = Array.from({ length: columnCount }, (_, index) => header.values[index] || `Column ${index + 1}`);
  return {
    headers,
    rows: data.map((record, index) => ({ key: index, line: record.line, values: record.values })),
  };
}
