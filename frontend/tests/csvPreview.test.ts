import { describe, expect, it } from 'vitest';
import { parseCsvPreview } from '../src/pages/InventoryImport/csvPreview';

describe('local RFC 4180 CSV preview', () => {
  it('handles BOM, commas, escaped quotes and quoted newlines while preserving physical line numbers', () => {
    const preview = parseCsvPreview('\uFEFFmaterial_code,quantity,unit\r\n"MAT,001",1,KG\r\n"MAT""002","2\r\n",PCS\r\n');

    expect(preview.headers).toEqual(['material_code', 'quantity', 'unit']);
    expect(preview.rows).toEqual([
      { key: 0, line: 2, values: ['MAT,001', '1', 'KG'] },
      { key: 1, line: 3, values: ['MAT"002', '2\r\n', 'PCS'] },
    ]);
  });

  it('rejects an unclosed quoted field instead of silently splitting it', () => {
    expect(() => parseCsvPreview('material_code,quantity,unit\n"MAT-001,1,KG')).toThrow('未關閉的引號');
  });
});
