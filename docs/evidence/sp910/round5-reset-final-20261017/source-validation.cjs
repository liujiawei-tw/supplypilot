'use strict';

const fs = require('node:fs');
const path = require('node:path');

const SOURCE_SAMPLES = Object.freeze({
  '12': 'supplier-contract-sup-001-v1.txt',
  '13': 'quotation-sup-002-v1.txt',
  '14': 'procurement-sop-v1.txt',
  '15': 'product-specification-mat-001-v1.txt',
  '16': 'quality-requirement-v1.txt',
});

/**
 * Mirror DocumentTextExtractor for the Windows CRLF files used by the r4
 * Browser/seed run. Force CRLF first so Git core.autocrlf cannot change the
 * semantic input, then apply the Java parser's exact normalization sequence.
 * String.length/slice intentionally use JavaScript UTF-16 code units, matching
 * Java String.length/substring offsets.
 */
function canonicalizeWindowsBackendText(source) {
  const windowsText = source.replace(/\r\n|\r|\n/g, '\r\n');
  const normalized = windowsText
    .replace(/\u0000/g, ' ')
    .replace(/[\t\x0B\f\r ]+/g, ' ')
    .replace(/ *\n+ */g, '\n');
  return normalized.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '');
}

function canonicalSampleForDocument(documentId, samplesDirectory) {
  const filename = SOURCE_SAMPLES[String(documentId)];
  if (!filename) return null;
  return canonicalizeWindowsBackendText(fs.readFileSync(path.join(samplesDirectory, filename), 'utf8'));
}

function validateSource(source, expectedDocumentIds, samplesDirectory) {
  const id = source?.resourceId;
  const normalizedId = typeof id === 'number' && Number.isSafeInteger(id)
    ? String(id)
    : typeof id === 'string' && /^\d+$/.test(id) ? id : null;
  const expectedIds = new Set((expectedDocumentIds || []).map(String));
  const sample = normalizedId ? canonicalSampleForDocument(normalizedId, samplesDirectory) : null;
  const validOffsets = Number.isInteger(source?.startOffset)
    && Number.isInteger(source?.endOffset)
    && source.startOffset >= 0
    && source.endOffset >= source.startOffset
    && Boolean(sample)
    && source.endOffset <= sample.length;
  const exactExcerpt = typeof source?.excerpt === 'string'
    && validOffsets
    && sample.slice(source.startOffset, source.endOffset) === source.excerpt;

  return {
    valid: source?.resourceType === 'DOCUMENT'
      && normalizedId !== null
      && expectedIds.has(normalizedId)
      && source?.version === '1.0'
      && source?.chunkIndex === 0
      && validOffsets
      && exactExcerpt,
    resourceTypeValid: source?.resourceType === 'DOCUMENT',
    resourceIdValid: normalizedId !== null && expectedIds.has(normalizedId),
    versionValid: source?.version === '1.0',
    chunkIndexValid: source?.chunkIndex === 0,
    offsetsValid: validOffsets,
    exactExcerpt,
    resourceId: normalizedId,
    canonicalUtf16Length: sample?.length ?? null,
  };
}

function validateQueryResult(result, expected) {
  if (result?.httpStatus !== 200 || result?.status !== expected.status) return false;
  if (expected.status === 'INSUFFICIENT_EVIDENCE') {
    return (result.sources || []).length === 0 && (result.answer || '') === '';
  }
  if (expected.status !== 'ANSWERED' || typeof result.answer !== 'string') return false;
  if (!expected.terms.every(term => result.answer.includes(term))) return false;
  const sources = result.sources || [];
  if (sources.length === 0) return false;
  const sourceResults = sources.map(source => validateSource(source, expected.documentIds, expected.samplesDirectory));
  const requiredIds = (expected.requiredDocumentIds || expected.documentIds).map(String);
  return sourceResults.every(source => source.valid)
    && sourceResults.some(source => requiredIds.includes(source.resourceId));
}

function queryFailureExitCode(expectedStatusPass) {
  return expectedStatusPass === true ? 0 : 2;
}

function phaseExitCode(results) {
  return results.reduce((exitCode, result) => Math.max(exitCode, queryFailureExitCode(result.expectedStatusPass)), 0);
}

module.exports = {
  SOURCE_SAMPLES,
  canonicalizeWindowsBackendText,
  canonicalSampleForDocument,
  validateSource,
  validateQueryResult,
  queryFailureExitCode,
  phaseExitCode,
};
