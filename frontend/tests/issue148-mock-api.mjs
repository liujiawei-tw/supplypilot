import { createServer } from 'node:http';

const HOST = '127.0.0.1';
const PORT = Number(process.env.ISSUE148_MOCK_PORT || 9090);
const TOKEN = 'issue148-mock-browser-token';
const TEST_LOGIN = Object.freeze({ username: 'ui-test', password: 'ui-test-only' });
let mode = 'normal';

const stamp = '2026-10-24T16:05:00Z';
const user = { id: 9001, username: TEST_LOGIN.username, displayName: '瀏覽器驗收帳號', role: 'ADMIN', canManageUsers: true, mustChangePassword: false };
const suppliers = [
  { id: 1, code: 'SUP-LONG-001', name: '台灣國際精密材料整合與先進製程供應服務股份有限公司', country: 'TW', reliabilityScore: 97, qualityScore: 96, status: 'ACTIVE', createdAt: stamp, updatedAt: stamp },
  { id: 2, code: 'SUP-002', name: '中華測試供應商有限公司', country: 'TW', reliabilityScore: 88, qualityScore: 91, status: 'ACTIVE', createdAt: stamp, updatedAt: stamp },
];
const materials = [
  { id: 1, code: 'MAT-000001-LONG', name: '高純度電子級特殊複合材料與長名稱顯示測試品', baseUnit: 'KG', safetyStock: 250000, status: 'ACTIVE', createdAt: stamp, updatedAt: stamp },
  { id: 2, code: 'MAT-000002', name: '測試用不鏽鋼零件', baseUnit: 'PCS', safetyStock: 500, status: 'ACTIVE', createdAt: stamp, updatedAt: stamp },
];
const supplierMaterials = [
  { id: 101, supplierId: 1, materialId: 1, unitPrice: 1234567.8912, moq: 125000.5, packageMultiple: 1000, leadTimeDays: 45, currency: 'TWD', status: 'ACTIVE', createdAt: stamp, updatedAt: stamp },
  { id: 102, supplierId: 2, materialId: 2, unitPrice: 999.5, moq: 100, packageMultiple: 10, leadTimeDays: 12, currency: 'TWD', status: 'ACTIVE', createdAt: stamp, updatedAt: stamp },
];
const stages = [
  ['NEW_REQUEST', 'DRAFT'],
  ['SOURCING', 'DRAFT'],
  ['APPROVAL', 'DRAFT'],
  ['APPROVED_PENDING_ORDER', 'APPROVED'],
  ['ORDER_FOLLOW_UP', 'ORDERED'],
  ['RECEIVING_INSPECTION', 'ORDERED'],
  ['PAYMENT_RECONCILIATION', 'RECEIVED'],
  ['COMPLETED', 'RECEIVED'],
];
const purchaseOrders = stages.map(([stage, status], index) => {
  const id = 901 + index;
  const paid = stage === 'COMPLETED';
  const ordered = status === 'ORDERED' || status === 'RECEIVED';
  return {
    id,
    poNumber: 'PO-2026-' + String(id).padStart(6, '0'),
    supplierId: index === 1 ? 2 : 1,
    supplierCode: index === 1 ? suppliers[1].code : suppliers[0].code,
    supplierName: index === 1 ? suppliers[1].name : suppliers[0].name,
    status,
    stage,
    orderedProgress: stage === 'ORDER_FOLLOW_UP' ? 'FOLLOW_UP' : stage === 'RECEIVING_INSPECTION' ? 'RECEIVING_INSPECTION' : null,
    expectedDeliveryDate: index < 3 ? null : '2026-10-25',
    supplierPromisedDeliveryDate: ordered ? '2026-10-27' : null,
    receivingInspection: { allItemsReceived: paid, allQuantitiesConfirmed: paid, allQualityAccepted: paid, note: null, inspectedBy: paid ? user.id : null, inspectedByDisplayName: paid ? user.displayName : null, inspectedByUsername: paid ? user.username : null, inspectedAt: paid ? stamp : null },
    paymentConfirmed: paid,
    paymentConfirmedBy: paid ? user.id : null,
    paymentConfirmedByDisplayName: paid ? user.displayName : null,
    paymentConfirmedByUsername: paid ? user.username : null,
    paymentConfirmedAt: paid ? stamp : null,
    items: [
      { id: 10000 + id, materialId: 1, materialCode: materials[0].code, materialName: materials[0].name, quantity: '1234567890.5000', unit: 'KG', unitPrice: '123456.7890', total: '152415787532388.3675', currency: 'TWD', recommendationId: null, supplierRecommendationResultId: null, supplierOverride: false, quantityOverride: false, supplierOverrideReason: null, quantityOverrideReason: null },
      { id: 20000 + id, materialId: 2, materialCode: materials[1].code, materialName: materials[1].name, quantity: '1250.0000', unit: 'PCS', unitPrice: '999.5000', total: '1249375.0000', currency: 'TWD', recommendationId: null, supplierRecommendationResultId: null, supplierOverride: false, quantityOverride: false, supplierOverrideReason: null, quantityOverrideReason: null },
    ],
    createdBy: user.id,
    createdByDisplayName: user.displayName,
    createdByUsername: user.username,
    assignedTo: user.id,
    assignedToDisplayName: user.displayName,
    assignedToUsername: user.username,
    createdAt: stamp,
    approvedBy: status === 'DRAFT' ? null : user.id,
    approvedByDisplayName: status === 'DRAFT' ? null : user.displayName,
    approvedAt: status === 'DRAFT' ? null : stamp,
    approvedByUsername: status === 'DRAFT' ? null : user.username,
    rejectReason: null,
    rejectedBy: null,
    rejectedByDisplayName: null,
    rejectedAt: null,
    rejectedByUsername: null,
    cancelReason: null,
    cancelledBy: null,
    cancelledByDisplayName: null,
    cancelledByUsername: null,
    cancelledAt: null,
    orderedBy: ordered ? user.id : null,
    orderedByDisplayName: ordered ? user.displayName : null,
    orderedByUsername: ordered ? user.username : null,
    orderedAt: ordered ? stamp : null,
    receivedBy: paid ? user.id : null,
    receivedByDisplayName: paid ? user.displayName : null,
    receivedByUsername: paid ? user.username : null,
    receivedAt: paid ? stamp : null,
    isOverdue: index === 4,
    updatedAt: stamp,
  };
});
const inventory = [
  { materialId: 1, materialCode: materials[0].code, materialName: materials[0].name, quantity: 1234567890123.5, unit: 'KG', updatedAt: stamp },
  { materialId: 2, materialCode: materials[1].code, materialName: materials[1].name, quantity: 125000, unit: 'PCS', updatedAt: stamp },
];
const transactions = [
  { id: 3001, materialId: 1, materialCode: materials[0].code, materialName: materials[0].name, unit: 'KG', type: 'PURCHASE_RECEIVE', quantity: '1234567890.5000', purchaseOrderId: 908, purchaseOrderNumber: 'PO-2026-000908', reason: null, createdBy: user.id, createdByDisplayName: user.displayName, createdAt: stamp },
];
const documents = [
  { id: 4001, documentType: 'SUPPLIER_CONTRACT', supplierId: 1, title: '長名稱供應合作條款—瀏覽器版面測試', version: '2026-v1', storagePath: 'mock://fixture/contract.pdf', status: 'READY', uploadedBy: user.id, uploadedAt: stamp, updatedAt: stamp },
];
const semanticReport = { passed: true, generationKey: 'mock-fixture-only', modelId: 'mock-no-provider', dimensions: 0, ready: 0, skipped: 0, failed: 0, requiresReview: 0, pending: 0, findings: [], auditId: null };

function emptyPage(query) {
  const page = Math.max(0, Number(query.get('page') || 0));
  const size = Math.max(1, Number(query.get('size') || 20));
  return { content: [], page, size, totalElements: 0, totalPages: 0 };
}
function page(rows, query) {
  if (mode === 'empty') return emptyPage(query);
  const pageIndex = Math.max(0, Number(query.get('page') || 0));
  const size = Math.max(1, Number(query.get('size') || 20));
  const start = pageIndex * size;
  return { content: rows.slice(start, start + size), page: pageIndex, size, totalElements: rows.length, totalPages: Math.ceil(rows.length / size) };
}
function send(res, status, value, extraHeaders = {}) {
  const body = JSON.stringify(value);
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Authorization, Content-Type', 'Access-Control-Allow-Methods': 'GET, POST, PATCH, PUT, OPTIONS', 'Cache-Control': 'no-store', ...extraHeaders });
  res.end(body);
}
function mockError(path) {
  return { code: 'MOCK_FIXTURE_ERROR', message: '隔離 Mock API 的錯誤狀態測試', traceId: 'issue148-mock', timestamp: stamp, path, httpStatus: 503 };
}
async function readBody(req) {
  let text = '';
  for await (const chunk of req) text += chunk;
  try { return text ? JSON.parse(text) : {}; } catch { return {}; }
}
function byQuery(rows, query) {
  let result = rows;
  const status = query.get('status');
  if (status) result = result.filter((row) => row.status === status);
  const codeFilter = query.get('code');
  if (codeFilter) result = result.filter((row) => [row.code, row.supplierCode, row.materialCode].some((value) => typeof value === 'string' && value.includes(codeFilter)));
  const nameFilter = query.get('name');
  if (nameFilter) result = result.filter((row) => [row.name, row.supplierName, row.materialName].some((value) => typeof value === 'string' && value.includes(nameFilter)));
  const supplierId = query.get('supplierId');
  if (supplierId) result = result.filter((row) => row.supplierId === Number(supplierId));
  const materialId = query.get('materialId');
  if (materialId) result = result.filter((row) => row.items ? row.items.some((item) => item.materialId === Number(materialId)) : row.materialId === Number(materialId));
  const code = query.get('materialCode');
  if (code) result = result.filter((row) => String(row.materialCode).includes(code));
  return result;
}
function authorized(req) {
  return req.headers.authorization === 'Bearer ' + TOKEN;
}
const server = createServer(async (req, res) => {
  const url = new URL(req.url || '/', 'http://' + HOST + ':' + PORT);
  const pathname = url.pathname;
  if (req.method === 'OPTIONS') { res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Authorization, Content-Type', 'Access-Control-Allow-Methods': 'GET, POST, PATCH, PUT, OPTIONS' }); res.end(); return; }
  if (pathname === '/__mock/state') {
    if (url.searchParams.has('mode')) {
      const nextMode = url.searchParams.get('mode');
      if (!['normal', 'empty', 'error', 'loading'].includes(nextMode)) { send(res, 400, { error: 'unsupported mock mode' }); return; }
      mode = nextMode;
    }
    send(res, 200, { fixture: true, mode, host: HOST, port: PORT });
    return;
  }
  if (mode === 'loading' && pathname.startsWith('/api/v1/') && pathname !== '/api/v1/auth/login' && pathname !== '/api/v1/auth/me') await new Promise((resolve) => setTimeout(resolve, 1600));
  if (mode === 'error' && pathname.startsWith('/api/v1/') && pathname !== '/api/v1/auth/login' && pathname !== '/api/v1/auth/me') { send(res, 503, mockError(pathname)); return; }
  if (pathname === '/api/v1/auth/login' && req.method === 'POST') {
    const body = await readBody(req);
    if (body.username !== TEST_LOGIN.username || body.password !== TEST_LOGIN.password) { send(res, 401, { code: 'BAD_CREDENTIALS', message: 'Mock 測試帳號不正確', traceId: 'issue148-mock', timestamp: stamp, path: pathname }); return; }
    send(res, 200, { accessToken: TOKEN, tokenType: 'Bearer', user });
    return;
  }
  if (pathname === '/api/v1/auth/me' && req.method === 'GET') {
    send(res, authorized(req) ? 200 : 401, authorized(req) ? user : { code: 'UNAUTHORIZED', message: 'Mock session required', traceId: 'issue148-mock', timestamp: stamp, path: pathname });
    return;
  }
  if (!pathname.startsWith('/api/v1/') || !authorized(req)) { send(res, 401, { code: 'UNAUTHORIZED', message: 'Mock session required', traceId: 'issue148-mock', timestamp: stamp, path: pathname }); return; }
  const path = pathname.slice('/api/v1'.length);
  if (req.method === 'GET' && path === '/users/assignable') { send(res, 200, [{ id: user.id, username: user.username, displayName: user.displayName }]); return; }
  if (req.method === 'GET' && path === '/purchase-orders') { send(res, 200, page(byQuery(purchaseOrders, url.searchParams), url.searchParams)); return; }
  if (req.method === 'GET' && /^\/purchase-orders\/\d+$/.test(path)) { send(res, 200, purchaseOrders.find((row) => row.id === Number(path.split('/')[2])) || mockError(path)); return; }
  if (req.method === 'GET' && path === '/receiving/purchase-orders') {
    const rows = purchaseOrders.filter((row) => row.status === 'ORDERED').map((row) => ({ id: row.id, poNumber: row.poNumber, supplierId: row.supplierId, supplierCode: row.supplierCode, supplierName: row.supplierName, status: row.status, orderedProgress: row.orderedProgress, receivingInspection: row.receivingInspection, expectedDeliveryDate: row.expectedDeliveryDate, isOverdue: row.isOverdue, items: row.items.map(({ id, materialId, materialCode, materialName, quantity, unit }) => ({ id, materialId, materialCode, materialName, quantity, unit })), orderedAt: row.orderedAt, receivedBy: row.receivedBy, receivedByDisplayName: row.receivedByDisplayName, receivedByUsername: row.receivedByUsername, receivedAt: row.receivedAt }));
    send(res, 200, page(byQuery(rows, url.searchParams), url.searchParams)); return;
  }
  if (req.method === 'GET' && path === '/suppliers') { send(res, 200, page(byQuery(suppliers, url.searchParams), url.searchParams)); return; }
  if (req.method === 'GET' && /^\/suppliers\/\d+$/.test(path)) { send(res, 200, suppliers.find((row) => row.id === Number(path.split('/')[2])) || mockError(path)); return; }
  if (req.method === 'GET' && path === '/supplier-materials') { send(res, 200, page(byQuery(supplierMaterials, url.searchParams), url.searchParams)); return; }
  if (req.method === 'GET' && path === '/materials') { send(res, 200, page(byQuery(materials, url.searchParams), url.searchParams)); return; }
  if (req.method === 'GET' && /^\/materials\/\d+$/.test(path)) { send(res, 200, materials.find((row) => row.id === Number(path.split('/')[2])) || mockError(path)); return; }
  if (req.method === 'GET' && path === '/inventory') {
    let rows = byQuery(inventory, url.searchParams);
    if (url.searchParams.get('materialCode')) rows = rows.filter((row) => row.materialCode.includes(url.searchParams.get('materialCode')));
    send(res, 200, page(rows, url.searchParams)); return;
  }
  if (req.method === 'GET' && /^\/inventory\/\d+$/.test(path)) { send(res, 200, inventory.find((row) => row.materialId === Number(path.split('/')[2])) || mockError(path)); return; }
  if (req.method === 'GET' && path === '/stock-transactions') { send(res, 200, page(transactions, url.searchParams)); return; }
  if (req.method === 'GET' && path === '/documents') { send(res, 200, page(documents, url.searchParams)); return; }
  if (req.method === 'GET' && /^\/documents\/\d+$/.test(path)) { send(res, 200, documents.find((row) => row.id === Number(path.split('/')[2])) || mockError(path)); return; }
  if (req.method === 'GET' && path === '/semantic-index/jobs') { send(res, 200, []); return; }
  if (req.method === 'GET' && path === '/semantic-index/readiness') { send(res, 200, semanticReport); return; }
  if (req.method === 'POST' && path === '/ai/query') {
    const body = await readBody(req);
    const message = typeof body.message === 'string' ? body.message : '';
    send(res, 200, { status: 'ANSWERED', answer: '【MOCKED UI FIXTURE】已收到問題：「' + message + '」。測試金額 TWD 1,234,567.80；此回答未呼叫 AI Provider。', tools: [], sources: [], answerSource: 'MOCKED_UI_FIXTURE' });
    return;
  }
  send(res, 404, mockError(path));
});
server.listen(PORT, HOST, () => process.stdout.write('Issue #148 fixture-only mock API listening on http://' + HOST + ':' + PORT + '/api/v1; no database, real credentials, or AI Provider\\n'));
server.on('error', (error) => { process.stderr.write(String(error) + '\\n'); process.exitCode = 1; });
process.on('SIGINT', () => server.close(() => process.exit(0)));
process.on('SIGTERM', () => server.close(() => process.exit(0)));
