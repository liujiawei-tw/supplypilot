const MAX_FILE_BYTES = 20 * 1024 * 1024;

const allowedFiles: Record<string, string> = {
  '.pdf': 'application/pdf',
  '.txt': 'text/plain',
  '.docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
};

export function validateDocumentFile(file: File | null): string | null {
  if (!file || file.size === 0) return '請選擇非空白文件。';
  if (file.size > MAX_FILE_BYTES) return '文件不可超過 20 MiB。';
  const extension = file.name.slice(file.name.lastIndexOf('.')).toLowerCase();
  const expectedType = allowedFiles[extension];
  if (!expectedType || file.type.toLowerCase() !== expectedType) return '僅允許內容類型相符的 PDF、TXT 或 DOCX 文件。';
  return null;
}
