import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { mkdir, writeFile } from 'node:fs/promises';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '../../..');
const frontendUrl = process.env.UI_D_FRONTEND_URL ?? 'http://127.0.0.1:5179';
const npmBin = process.env.PATH.split(path.delimiter).find((entry) => entry.includes(`${path.sep}_npx${path.sep}`) && path.basename(entry) === '.bin');
if (!npmBin) throw new Error('Run this script through npm exec --package=playwright so its .bin directory is on PATH.');
const requireFromPlaywright = createRequire(path.join(npmBin, 'ui-d-runner.cjs'));
const { chromium } = requireFromPlaywright('playwright');
const screenshotsDir = path.join(here, 'screenshots');
await mkdir(screenshotsDir, { recursive: true });

const createdAt = new Date().toISOString();
const updatedAt = createdAt;
const evidence = {
  generatedAt: createdAt,
  gitSha: process.env.UI_D_GIT_SHA ?? '未提供',
  browser: 'Microsoft Edge via Playwright channel=msedge, headed',
  playwrightVersion: requireFromPlaywright('playwright/package.json').version,
  fixtureMode: true,
  backendIntegration: false,
  physicalDeviceTest: false,
  requests: [],
  cases: [],
  pageErrors: [],
};

const suppliers = Array.from({ length: 120 }, (_, index) => ({
  id: index + 1,
  code: `SUP-${String(index + 1).padStart(3, '0')}`,
  name: index === 119 ? `長名稱供應商 ${index + 1} ${'企業採購測試'.repeat(12)}` : `Fixture Supplier ${index + 1}`,
  country: 'TW', reliabilityScore: 90, qualityScore: 95, status: 'ACTIVE', createdAt, updatedAt: createdAt,
}));
const materials = Array.from({ length: 120 }, (_, index) => ({
  id: index + 1,
  code: `MAT-${String(index + 1).padStart(3, '0')}`,
  name: index === 100 || index === 101 ? 'Copper Wire' : index === 119 ? `長名稱物料 ${index + 1} ${'包裝規格測試'.repeat(12)}` : `Fixture Material ${index + 1}`,
  baseUnit: index % 2 ? 'PCS' : 'KG', safetyStock: 0, status: 'ACTIVE', createdAt, updatedAt,
}));

const longTitle = `長名稱文件 UI-D Fixture ${'供應鏈品質規範與供應商作業程序'.repeat(8)}`;
const document = {
  id: 501, title: longTitle, documentType: 'PROCUREMENT_SOP', supplierId: 120, version: 'v2026.10',
  status: 'READY', uploadedBy: 'Fixture Buyer', uploadedAt: createdAt, updatedAt: createdAt,
};
const po = {
  id: 701, poNumber: 'PO-UI-D-701', supplierId: 120, supplierCode: suppliers[119].code, supplierName: suppliers[119].name,
  status: 'DRAFT', expectedDeliveryDate: '2026-11-15', items: [{
    id: 1, materialId: 101, materialCode: materials[100].code, materialName: materials[100].name,
    quantity: '12.0000', unit: materials[100].baseUnit, unitPrice: '4.2500', total: '51.0000',
    recommendationId: null, supplierRecommendationResultId: null, supplierOverride: false,
    quantityOverride: false, supplierOverrideReason: null, quantityOverrideReason: null,
  }],
  createdBy: 1, createdByDisplayName: 'Fixture Buyer', createdAt, approvedBy: null, approvedByDisplayName: null,
  approvedAt: null, rejectReason: null, rejectedBy: null, rejectedByDisplayName: null, rejectedAt: null,
  orderedBy: null, orderedByDisplayName: null, orderedAt: null, receivedBy: null,
  receivedByDisplayName: null, receivedAt: null, isOverdue: false, updatedAt: createdAt,
};

const manager = { id: 1, username: 'ui-d-buyer', displayName: 'UI-D Fixture Buyer', role: 'PROCUREMENT_STAFF' };
const listResponse = (content, page, size, total) => ({
  content, page, size, totalElements: total, totalPages: Math.ceil(total / size),
});

const browser = await chromium.launch({ channel: 'msedge', headless: false });
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
await context.addInitScript(() => localStorage.setItem('supplypilot.accessToken', 'ui-d-controlled-fixture-token'));
const page = await context.newPage();
page.on('pageerror', (error) => evidence.pageErrors.push(error.message));
evidence.edgeVersion = browser.version();

await page.route('**/api/v1/**', async (route) => {
  const request = route.request();
  const url = new URL(request.url());
  const pathName = url.pathname.replace(/^\/api\/v1/, '');
  const params = Object.fromEntries(url.searchParams.entries());
  let status = 200;
  let body;
  if (pathName === '/auth/me') body = manager;
  else if (pathName === '/purchase-orders' && request.method() === 'GET') body = listResponse([po], Number(params.page ?? 0), Number(params.size ?? 20), 1);
  else if (pathName === '/purchase-orders' && request.method() === 'POST') {
    evidence.poDraftPayload = JSON.parse(request.postData() ?? '{}');
    body = { ...po, supplierId: evidence.poDraftPayload.supplierId };
  } else if (pathName === '/suppliers' || pathName === '/materials') {
    const records = pathName === '/suppliers' ? suppliers : materials;
    const filtered = records.filter((record) => (!params.code || record.code.includes(params.code)) && (!params.name || record.name.includes(params.name)));
    const pageIndex = Number(params.page ?? 0); const size = Number(params.size ?? 20);
    body = listResponse(filtered.slice(pageIndex * size, (pageIndex + 1) * size), pageIndex, size, filtered.length);
  } else if (/^\/suppliers\/\d+$/.test(pathName)) body = suppliers[Number(pathName.split('/').at(-1)) - 1];
  else if (/^\/materials\/\d+$/.test(pathName)) body = materials[Number(pathName.split('/').at(-1)) - 1];
  else if (pathName === '/documents') body = listResponse([document], Number(params.page ?? 0), Number(params.size ?? 20), 1);
  else if (pathName === '/documents/501') body = document;
  else if (pathName === '/inventory') body = listResponse([], Number(params.page ?? 0), Number(params.size ?? 20), 0);
  else { status = 404; body = { code: 'NOT_FOUND', message: `No Fixture for ${request.method()} ${pathName}`, traceId: '', timestamp: createdAt, path: pathName }; }
  evidence.requests.push({ method: request.method(), path: pathName, query: params, status });
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
});

const recordCase = (id, viewport, result, extra = {}) => evidence.cases.push({ id, viewport, status: result ? 'PASS' : 'FAIL', ...extra });
const saveShot = async (name) => {
  const file = path.join(screenshotsDir, name);
  await page.screenshot({ path: file, fullPage: true });
  return path.relative(here, file).replaceAll('\\', '/');
};
const waitForFixture = async () => page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});

try {
  for (const viewport of [{ width: 375, height: 812 }, { width: 768, height: 1024 }, { width: 1440, height: 1000 }]) {
    await page.setViewportSize(viewport);
    await page.goto(`${frontendUrl}/purchase-orders`);
    await waitForFixture();
    await page.getByRole('button', { name: '建立手動草稿' }).waitFor();
    const createButton = page.getByRole('button', { name: '建立手動草稿' });
    await createButton.focus();
    await page.keyboard.press('Enter');
    const dialog = page.getByRole('dialog', { name: '建立手動採購單草稿' });
    await dialog.waitFor();
    const modalGeometry = await dialog.evaluate((element) => {
      const rect = element.getBoundingClientRect();
      return { left: rect.left, right: rect.right, width: rect.width, viewportWidth: innerWidth, scrollWidth: element.scrollWidth };
    });
    const modalFits = modalGeometry.left >= 0 && modalGeometry.right <= modalGeometry.viewportWidth + 1;
    const screenshot = await saveShot(`po-modal-${viewport.width}.png`);
    const activeInsideDialog = await page.evaluate(() => !!document.activeElement?.closest('[role="dialog"]'));
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);
    const focusReturned = await createButton.evaluate((element) => document.activeElement === element);
    await createButton.click();
    const reopenedDialog = page.getByRole('dialog', { name: '建立手動採購單草稿' });
    const supplierBox = reopenedDialog.getByRole('combobox', { name: '選取供應商' });
    await supplierBox.focus();
    await supplierBox.fill('SUP-120');
    const supplierOption = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: 'SUP-120' });
    await supplierOption.waitFor({ state: 'visible' });
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    const materialBox = reopenedDialog.getByRole('combobox', { name: '選取物料' });
    await materialBox.focus();
    await materialBox.fill('MAT-101');
    const materialOption = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: 'MAT-101 — Copper Wire' });
    await materialOption.waitFor({ state: 'visible' });
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await reopenedDialog.getByRole('textbox', { name: '採購單品項 1 數量' }).fill('12');
    await reopenedDialog.getByRole('textbox', { name: '採購單品項 1 單價' }).fill('4.2500');
    await reopenedDialog.getByRole('button', { name: '檢視新草稿' }).click();
    await page.getByRole('dialog', { name: '確認建立手動草稿' }).getByRole('button', { name: '確認建立草稿' }).click();
    await waitForFixture();
    const payload = evidence.requests.find((request) => request.method === 'POST' && request.path === '/purchase-orders');
    const idCorrect = payload !== undefined
      && evidence.poDraftPayload?.supplierId === 120
      && evidence.poDraftPayload.items?.[0]?.materialId === 101;
    recordCase(`po-create-${viewport.width}`, viewport, modalFits && activeInsideDialog && focusReturned && idCorrect, {
      checks: { modalFits, modalGeometry, activeInsideDialog, focusReturned, idCorrect, source: 'Edge + controlled API Fixture' }, screenshot,
    });
  }

  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(`${frontendUrl}/purchase-orders`);
  await waitForFixture();
  const poTableViewport = await page.locator('.ant-table-content').evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { left: rect.left, right: rect.right, width: rect.width, clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth, overflowX: getComputedStyle(element).overflowX,
      pageScrollWidth: document.documentElement.scrollWidth, viewportWidth: innerWidth };
  });
  await page.locator('.ant-table-content').evaluate((element) => { element.scrollLeft = element.scrollWidth; });
  const poTableCanScrollHorizontally = await page.locator('.ant-table-content').evaluate((element) => element.scrollWidth <= element.clientWidth || element.scrollLeft > 0);
  recordCase('po-list-horizontal-scroll-375', { width: 375, height: 812 }, poTableCanScrollHorizontally, {
    checks: { ...poTableViewport, poTableCanScrollHorizontally }, screenshot: await saveShot('po-list-375.png'),
  });
  const siderTrigger = page.locator('.ant-layout-sider-zero-width-trigger');
  const siderTriggerVisible = await siderTrigger.isVisible().catch(() => false);
  let sidebarOpens = false;
  if (siderTriggerVisible) {
    await siderTrigger.click();
    sidebarOpens = await page.locator('.ant-layout-sider').evaluate((element) => element.getBoundingClientRect().width > 0);
    await siderTrigger.click().catch(() => {});
  }
  recordCase('sidebar-toggle-375', { width: 375, height: 812 }, siderTriggerVisible && sidebarOpens, {
    checks: { siderTriggerVisible, sidebarOpens }, screenshot: await saveShot('sidebar-375.png'),
  });

  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto(`${frontendUrl}/documents`);
  await waitForFixture();
  await page.getByRole('button', { name: '#501' }).click();
  await page.getByRole('complementary').waitFor().catch(() => {});
  const documentDrawer = page.locator('.ant-drawer-content-wrapper').filter({ has: page.getByText('文件明細', { exact: true }) });
  await documentDrawer.getByText(longTitle, { exact: true }).first().waitFor();
  const documentDrawerGeometry = await documentDrawer.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { left: rect.left, right: rect.right, width: rect.width, viewportWidth: innerWidth, scrollWidth: element.scrollWidth };
  });
  const documentDrawerFits = documentDrawerGeometry.left >= 0 && documentDrawerGeometry.right <= documentDrawerGeometry.viewportWidth + 1;
  recordCase('document-drawer-375', { width: 375, height: 812 }, documentDrawerFits, {
    checks: { documentDrawerFits, documentDrawerGeometry, longTitleVisible: await documentDrawer.getByText(longTitle, { exact: true }).first().isVisible() },
    screenshot: await saveShot('document-drawer-375.png'),
  });

  await page.goto(`${frontendUrl}/purchase-orders`);
  await waitForFixture();
  const viewportResults = [];
  for (const viewport of [{ width: 375, height: 812 }, { width: 768, height: 1024 }, { width: 1440, height: 1000 }]) {
    await page.setViewportSize(viewport);
    const headerGeometry = await page.locator('.ant-layout-header').evaluate((element) => {
      const rect = element.getBoundingClientRect();
      return { left: rect.left, right: rect.right, width: rect.width, scrollWidth: element.scrollWidth, viewportWidth: innerWidth };
    });
    const aiButton = page.getByRole('button', { name: 'AI 助理' });
    const aiButtonGeometry = await aiButton.evaluate((element) => {
      const rect = element.getBoundingClientRect();
      return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom };
    });
    const headerFits = headerGeometry.right <= viewport.width + 1
      && headerGeometry.scrollWidth <= headerGeometry.width + 1
      && aiButtonGeometry.left >= 0 && aiButtonGeometry.right <= viewport.width + 1;
    const aiButtonVisible = await page.getByRole('button', { name: 'AI 助理' }).isVisible();
    if (viewport.width === 375) {
      await page.getByRole('button', { name: 'AI 助理' }).click();
      const aiDrawer = page.locator('.ant-drawer-content-wrapper').filter({ has: page.getByText('SupplyPilot AI 助理', { exact: true }) });
      await aiDrawer.waitFor();
      await page.waitForTimeout(500);
      const aiGeometry = await aiDrawer.evaluate((element) => {
        const rect = element.getBoundingClientRect();
        return { left: rect.left, right: rect.right, width: rect.width, viewportWidth: innerWidth };
      });
      recordCase('ai-drawer-375', viewport, aiGeometry.left >= 0 && aiGeometry.right <= viewport.width + 1, {
        checks: { aiGeometry, aiDrawerFits: aiGeometry.left >= 0 && aiGeometry.right <= viewport.width + 1 },
        screenshot: await saveShot('ai-drawer-375.png'),
      });
      await page.keyboard.press('Escape');
    }
    viewportResults.push({ viewport, headerFits, headerGeometry, aiButtonGeometry, aiButtonVisible });
  }
  for (const result of viewportResults) recordCase(`app-header-${result.viewport.width}`, result.viewport, result.headerFits && result.aiButtonVisible, result);

  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(`${frontendUrl}/dashboard`);
  await waitForFixture();
  const zoomAttempts = [];
  for (let index = 0; index < 8; index += 1) {
    const zoom = await page.evaluate(() => ({ width: innerWidth, dpr: devicePixelRatio }));
    if (zoom.dpr >= 1.95) break;
    await page.keyboard.press('Control+Plus').catch(() => page.keyboard.press('Control+Shift+Equal'));
    await page.waitForTimeout(100);
    zoomAttempts.push(await page.evaluate(() => ({ width: innerWidth, dpr: devicePixelRatio })));
  }
  const zoomResult = await page.evaluate(() => ({ cssViewport: { width: innerWidth, height: innerHeight }, devicePixelRatio, visualViewportScale: visualViewport?.scale }));
  const browserZoomAtLeast200 = zoomResult.devicePixelRatio >= 1.95;
  recordCase('browser-zoom-200', { width: 1440, height: 1000, method: 'headed Edge Ctrl+Plus repeated; fallback Ctrl+Shift+Equal' }, browserZoomAtLeast200, {
    status: browserZoomAtLeast200 ? 'PASS' : 'BLOCKED',
    checks: { browserZoomAtLeast200, zoomResult, zoomAttempts, effectiveCssViewport: zoomResult.cssViewport,
      reason: browserZoomAtLeast200 ? undefined : 'Playwright page keyboard did not control Edge browser chrome; innerWidth and devicePixelRatio remained unchanged. CUA stopped after it could not verify the active browser URL.' },
    screenshot: await saveShot('browser-zoom-200.png'),
  });
} catch (error) {
  evidence.error = error.stack ?? String(error);
  process.exitCode = 1;
} finally {
  evidence.completedAt = new Date().toISOString();
  evidence.screenshotDirectory = 'screenshots/';
  await writeFile(path.join(here, 'edge-fixture-manifest.json'), `${JSON.stringify(evidence, null, 2)}\n`, 'utf8');
  await browser.close();
}
