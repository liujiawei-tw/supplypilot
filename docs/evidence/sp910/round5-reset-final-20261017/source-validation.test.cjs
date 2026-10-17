'use strict';

const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const {
  canonicalSampleForDocument,
  canonicalizeWindowsBackendText,
  phaseExitCode,
  queryFailureExitCode,
  validateQueryResult,
  validateSource,
} = require('./source-validation.cjs');

const evidenceDirectory = __dirname;
const repositoryRoot = path.resolve(__dirname, '../../../..');
const samplesDirectory = path.join(repositoryRoot, 'samples', 'documents');
const core = JSON.parse(fs.readFileSync(path.join(evidenceDirectory, 'reset-final-query-results.json'), 'utf8'));
const followup = JSON.parse(fs.readFileSync(path.join(evidenceDirectory, 'remaining-gemini-query-results.json'), 'utf8'));
const allResults = [core.queries[0], ...followup.queries];

const expected = {
  'SUP-001-leadtime': {status: 'ANSWERED', documentIds: ['12'], requiredDocumentIds: ['12'], terms: ['5', '曆日', 'ORDERED']},
  'SUP-002-validity': {status: 'ANSWERED', documentIds: ['13'], requiredDocumentIds: ['13'], terms: ['30', '曆日']},
  'MAT-001-storage': {status: 'ANSWERED', documentIds: ['15'], requiredDocumentIds: ['15'], terms: ['KG', '陰涼乾燥', '陽光直射']},
  'SOP-receiving-role': {status: 'ANSWERED', documentIds: ['14', '16'], requiredDocumentIds: ['14'], terms: ['倉庫人員']},
  'quality-quarantine': {status: 'ANSWERED', documentIds: ['16'], requiredDocumentIds: ['16'], terms: ['缺少批次識別', '封條破損', '污染', '物料不符']},
  'insurance-insufficient': {status: 'INSUFFICIENT_EVIDENCE'},
};

function check(result) {
  const policy = expected[result.caseId];
  assert.ok(policy, `Unexpected case ${result.caseId}`);
  return validateQueryResult(result, {...policy, samplesDirectory});
}

function mustReject(mutated, policy) {
  assert.equal(validateQueryResult(mutated, {...policy, samplesDirectory}), false);
}

for (const [documentId, expectedLength] of Object.entries({'12': 336, '13': 343, '14': 383, '15': 268, '16': 269})) {
  assert.equal(canonicalSampleForDocument(documentId, samplesDirectory).length, expectedLength,
    `Backend-compatible UTF-16 length for document ${documentId}`);
}
assert.equal(canonicalizeWindowsBackendText('A\r\n\r\n😀B').length, 6,
  'UTF-16 length counts emoji as two code units and preserves paragraph separation');

assert.equal(allResults.length, 6);
for (const result of allResults) assert.equal(check(result), true, `${result.caseId} must validate`);
const revalidatedResults = allResults.map(result => ({...result, expectedStatusPass: check(result)}));
assert.equal(phaseExitCode(revalidatedResults), 0);

const sup001 = structuredClone(allResults[0]);
const supPolicy = {...expected[sup001.caseId], samplesDirectory};
const originalSource = sup001.sources[0];

for (const [name, change] of [
  ['wrong resource type', s => ({...s, resourceType: 'RECOMMENDATION'})],
  ['wrong document id', s => ({...s, resourceId: '999'})],
  ['wrong version', s => ({...s, version: '2.0'})],
  ['wrong chunk', s => ({...s, chunkIndex: 1})],
  ['out of range offset', s => ({...s, endOffset: 9999})],
  ['reversed offset', s => ({...s, startOffset: 20, endOffset: 10})],
  ['offset/excerpt length mismatch', s => ({...s, endOffset: s.endOffset - 1})],
  ['altered excerpt', s => ({...s, excerpt: `${s.excerpt} altered`})],
  ['citation with leading whitespace', s => ({...s, excerpt: ` ${s.excerpt}`})],
]) {
  const mutated = structuredClone(sup001);
  mutated.sources[0] = change(originalSource);
  mustReject(mutated, supPolicy);
}

const multiSource = structuredClone(allResults.find(result => result.caseId === 'SOP-receiving-role'));
multiSource.sources[1].excerpt = `${multiSource.sources[1].excerpt} altered`;
mustReject(multiSource, {...expected[multiSource.caseId], samplesDirectory});

const insufficientLaundered = structuredClone(allResults.find(result => result.caseId === 'insurance-insufficient'));
insufficientLaundered.status = 'ANSWERED';
insufficientLaundered.answer = '有費率';
mustReject(insufficientLaundered, {...expected[insufficientLaundered.caseId], samplesDirectory});

const missingAnswerTerms = structuredClone(sup001);
missingAnswerTerms.answer = '交期尚未提供';
mustReject(missingAnswerTerms, supPolicy);

const followupWithAssertionFailure = structuredClone(followup.queries);
followupWithAssertionFailure[2].expectedStatusPass = false;
assert.equal(queryFailureExitCode(followupWithAssertionFailure[2].expectedStatusPass), 2,
  'The Playwright harness failure gate must return exit 2 for every query phase');
assert.equal(phaseExitCode(followupWithAssertionFailure), 2,
  'Any query-followup assertion failure must produce a non-zero process exit');

const output = {
  capturedAt: new Date().toISOString(),
  timezone: 'Asia/Taipei',
  baseGitHead: cp.execFileSync('git', ['rev-parse', 'HEAD'], {cwd: repositoryRoot, encoding: 'utf8'}).trim(),
  status: 'PASS',
  kind: 'offline evidence revalidation; no Browser, Gemini, Backend, DB, or Reset executed',
  realResponseCasesValidated: revalidatedResults.map(result => ({
    caseId: result.caseId,
    httpStatus: result.httpStatus,
    status: result.status,
    sourceCount: result.sources.length,
    sources: result.sources.map(source => ({resourceType: source.resourceType, resourceId: source.resourceId,
      version: source.version, chunkIndex: source.chunkIndex, startOffset: source.startOffset,
      endOffset: source.endOffset, valid: validateSource(source, expected[result.caseId].documentIds || [], samplesDirectory).valid})),
    offlineAssertionPass: result.expectedStatusPass,
  })),
  canonicalUtf16Lengths: {'12': 336, '13': 343, '14': 383, '15': 268, '16': 269},
  positiveCases: allResults.length,
  negativeAssertions: 14,
  queryFollowupFailureExitCode: phaseExitCode(followupWithAssertionFailure),
};
fs.writeFileSync(path.join(evidenceDirectory, 'source-validation-offline-results.json'), JSON.stringify(output, null, 2) + '\n');
console.log(JSON.stringify(output, null, 2));
