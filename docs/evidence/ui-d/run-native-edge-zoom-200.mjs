import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { existsSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';

const here = path.dirname(fileURLToPath(import.meta.url));
const evidenceDir = path.join(here, 'screenshots');
const frontendUrl = process.env.UI_D_FRONTEND_URL ?? 'http://127.0.0.1:5181';
const profilePath = process.env.UI_D_PROFILE_PATH;
const cdpEndpoint = process.env.UI_D_CDP_ENDPOINT;
const sessionPath = process.env.UI_D_SESSION_PATH;
const pageStatePath = process.env.UI_D_PAGE_STATE_PATH;
const goPath = process.env.UI_D_GO_PATH;
const resultPath = process.env.UI_D_RESULT_PATH;
const playwrightPackage = process.env.UI_D_PLAYWRIGHT_PACKAGE;
if (![profilePath, sessionPath, pageStatePath, goPath, resultPath, playwrightPackage].every(Boolean)) {
  throw new Error('Missing one or more UI_D_* runtime paths.');
}

const requireFromPlaywright = createRequire(path.join(playwrightPackage, 'ui-d-native-runner.cjs'));
const { chromium } = requireFromPlaywright('playwright');
const playwrightVersion = requireFromPlaywright('playwright/package.json').version;
const createdAt = new Date().toISOString();
const now = () => new Date().toISOString();
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
  baseUnit: index % 2 ? 'PCS' : 'KG', safetyStock: 0, status: 'ACTIVE', createdAt, updatedAt: createdAt,
}));
const longTitle = `長名稱文件 UI-D Fixture ${'供應鏈品質規範與供應商作業程序'.repeat(8)}`;
const documentFixture = {
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
const buyer = { id: 1, username: 'ui-d-buyer', displayName: 'UI-D Fixture Buyer', role: 'PROCUREMENT_STAFF' };
const listResponse = (content, page, size, total) => ({ content, page, size, totalElements: total, totalPages: Math.ceil(total / size) });
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const writeJson = (file, value) => writeFile(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
const pageErrors = [];
const requests = [];
const result = {
  createdAt,
  fixtureMode: true,
  backendIntegration: false,
  physicalDeviceTest: false,
  role: buyer.role,
  url: `${frontendUrl}/purchase-orders`,
  playwrightVersion,
  pageErrors,
  requests,
  cases: [],
};

await mkdir(evidenceDir, { recursive: true });
const browser = cdpEndpoint ? await chromium.connectOverCDP(cdpEndpoint) : null;
if (cdpEndpoint && browser.contexts().length !== 1) {
  throw new Error(`Expected one isolated Edge BrowserContext on ${cdpEndpoint}; found ${browser.contexts().length}`);
}
const context = browser ? browser.contexts()[0] : await chromium.launchPersistentContext(profilePath, {
  channel: 'msedge',
  headless: false,
  viewport: null,
});
const targetUrl = `${frontendUrl}/purchase-orders`;
const contextPages = context.pages();
let page;
if (cdpEndpoint) {
  const existingTargets = contextPages.filter((entry) => entry.url() === targetUrl);
  const expectedOrigin = new URL(frontendUrl).origin;
  const unrelatedWebPages = contextPages.filter((entry) => /^https?:/i.test(entry.url()) && new URL(entry.url()).origin !== expectedOrigin);
  const blankPages = contextPages.filter((entry) => entry.url() === 'about:blank');
  if (existingTargets.length > 1 || unrelatedWebPages.length > 0) {
    throw new Error(`CDP context is not unique to this fixture; target pages=${existingTargets.length}, unrelated web pages=${unrelatedWebPages.map((entry) => entry.url()).join(', ')}`);
  }
  if (existingTargets.length === 1) page = existingTargets[0];
  else if (blankPages.length === 1) page = blankPages[0];
  else page = await context.newPage();
} else page = contextPages[0] ?? await context.newPage();
await context.addInitScript(() => localStorage.setItem('supplypilot.accessToken', 'ui-d-controlled-fixture-token'));
page.on('pageerror', (error) => pageErrors.push(error.message));
result.edgeVersion = browser?.version() ?? context.browser()?.version() ?? 'unknown';
result.browserContext = cdpEndpoint
  ? `connectOverCDP(${cdpEndpoint}), unique external userDataDir, viewport=null`
  : 'launchPersistentContext, independent userDataDir, viewport=null';

await page.route('**/api/v1/**', async (route) => {
  const request = route.request();
  const url = new URL(request.url());
  const requestPath = url.pathname.replace(/^\/api\/v1/, '');
  const params = Object.fromEntries(url.searchParams.entries());
  let status = 200;
  let body;
  if (requestPath === '/auth/me') body = buyer;
  else if (requestPath === '/purchase-orders' && request.method() === 'GET') {
    body = listResponse([po], Number(params.page ?? 0), Number(params.size ?? 20), 1);
  } else if (requestPath === '/purchase-orders' && request.method() === 'POST') {
    result.poDraftPayload = JSON.parse(request.postData() ?? '{}');
    body = { ...po, supplierId: result.poDraftPayload.supplierId, items: result.poDraftPayload.items };
  } else if (requestPath === '/suppliers' || requestPath === '/materials') {
    const records = requestPath === '/suppliers' ? suppliers : materials;
    const filtered = records.filter((record) => (!params.code || record.code.includes(params.code))
      && (!params.name || record.name.includes(params.name)));
    const pageIndex = Number(params.page ?? 0);
    const size = Number(params.size ?? 20);
    body = listResponse(filtered.slice(pageIndex * size, (pageIndex + 1) * size), pageIndex, size, filtered.length);
  } else if (/^\/suppliers\/\d+$/.test(requestPath)) body = suppliers[Number(requestPath.split('/').at(-1)) - 1];
  else if (/^\/materials\/\d+$/.test(requestPath)) body = materials[Number(requestPath.split('/').at(-1)) - 1];
  else if (requestPath === '/documents') body = listResponse([documentFixture], Number(params.page ?? 0), Number(params.size ?? 20), 1);
  else if (requestPath === '/documents/501') body = documentFixture;
  else if (requestPath === '/inventory') body = listResponse([], Number(params.page ?? 0), Number(params.size ?? 20), 0);
  else {
    status = 404;
    body = { code: 'NOT_FOUND', message: `No Fixture for ${request.method()} ${requestPath}`, traceId: '', timestamp: now(), path: requestPath };
  }
  requests.push({ method: request.method(), path: requestPath, query: params, status });
  await route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
});

const metrics = async () => page.evaluate(() => ({
  measuredAt: new Date().toISOString(),
  url: location.href,
  innerWidth,
  innerHeight,
  devicePixelRatio,
  visualViewport: visualViewport ? {
    width: visualViewport.width,
    height: visualViewport.height,
    scale: visualViewport.scale,
    offsetLeft: visualViewport.offsetLeft,
    offsetTop: visualViewport.offsetTop,
  } : null,
  screen: { width: screen.width, height: screen.height, availWidth: screen.availWidth, availHeight: screen.availHeight },
  document: { clientWidth: document.documentElement.clientWidth, scrollWidth: document.documentElement.scrollWidth,
    clientHeight: document.documentElement.clientHeight, scrollHeight: document.documentElement.scrollHeight },
  activeElement: document.activeElement?.getAttribute('aria-label') ?? document.activeElement?.textContent?.trim()?.slice(0, 100) ?? null,
}));
const savePage = async (filename) => {
  const parsed = path.parse(filename);
  const suffix = process.env.UI_D_CAPTURE_TAG ? `-${process.env.UI_D_CAPTURE_TAG}` : '';
  const screenshot = path.join(evidenceDir, `${parsed.name}${suffix}${parsed.ext}`);
  await page.screenshot({ path: screenshot, fullPage: false });
  return path.relative(here, screenshot).replaceAll('\\', '/');
};
const addCase = (id, status, checks) => result.cases.push({ id, status, checks });
const assert = (condition, message) => { if (!condition) throw new Error(message); };

try {
  await page.goto(targetUrl, { waitUntil: 'domcontentloaded', timeout: 20_000 });
  await page.getByRole('button', { name: '建立手動草稿' }).waitFor({ timeout: 20_000 });
  const authFixtureRequest = requests.find((request) => request.method === 'GET' && request.path === '/auth/me' && request.status === 200);
  assert(authFixtureRequest, 'The page did not receive a successful /auth/me response from the controlled Fixture route');
  result.initialPage = await metrics();
  await writeJson(sessionPath, {
    readyAt: now(),
    nodePid: process.pid,
    profilePath,
    url: page.url(),
    edgeVersion: result.edgeVersion,
    playwrightVersion,
    cdpEndpoint: cdpEndpoint ?? null,
    fixtureAuthMeStatus: authFixtureRequest.status,
    fixtureRoutingActive: true,
    viewportOption: null,
    initialPage: result.initialPage,
  });

  while (!existsSync(goPath)) {
    await writeJson(pageStatePath, await metrics());
    await sleep(250);
  }
  const go = JSON.parse(await readFile(goPath, 'utf8'));
  assert(go.action === 'run-200-percent-checks', 'Unexpected runner control action');
  result.zoom = go.zoom;
  result.nativeWindow = go.nativeWindow;
  result.osDisplay = go.osDisplay;
  result.zoomScreenshot = go.zoomScreenshot;
  result.beforeZoom = go.beforeZoom;
  result.profilePageAtSessionStart = go.profilePageAtSessionStart;
  result.pageStateBeforeInteraction = go.pageStateBeforeInteraction;
  result.afterZoomMenuClosed = await metrics();
  assert(result.zoom?.actualPercent === 200, 'Native Edge UI did not confirm exactly 200%');
  assert(Math.abs(result.afterZoomMenuClosed.devicePixelRatio / result.beforeZoom.devicePixelRatio - 2) < 0.03,
    'devicePixelRatio did not approximately double from the 100% baseline');

  const header = page.locator('.ant-layout-header');
  const aiButton = page.getByRole('button', { name: 'AI 助理' });
  const headerCheck = await header.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, width: rect.width,
      scrollWidth: element.scrollWidth, viewportWidth: innerWidth, viewportHeight: innerHeight };
  });
  const aiButtonCheck = await aiButton.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom,
      inViewport: rect.left >= 0 && rect.right <= innerWidth && rect.top >= 0 && rect.bottom <= innerHeight };
  });
  const aiButtonVisible = await aiButton.isVisible();
  const headerScreenshot = await savePage('app-header-200-playwright.png');
  const headerOperable = aiButtonVisible && aiButtonCheck.inViewport;
  addCase('app-header-200', headerOperable ? 'PASS' : 'FAIL', {
    header: headerCheck, aiButton: { ...aiButtonCheck, visible: aiButtonVisible }, screenshot: headerScreenshot,
  });
  assert(headerOperable, 'App Header AI button is not operable in viewport at 200%');

  const sidebarTrigger = page.locator('.ant-layout-sider-zero-width-trigger');
  let sidebarOpens = false;
  if (await sidebarTrigger.isVisible().catch(() => false)) {
    await sidebarTrigger.click();
    sidebarOpens = await page.locator('.ant-layout-sider').evaluate((element) => element.getBoundingClientRect().width > 0);
  }
  assert(sidebarOpens, 'Sidebar did not open at 200%');
  const sidebarShot = await savePage('sidebar-open-200-playwright.png');
  addCase('sidebar-open-200', 'PASS', { sidebarOpens, screenshot: sidebarShot });
  const dashboardItem = page.getByRole('menuitem', { name: /儀表板/ });
  await dashboardItem.waitFor({ state: 'visible' });
  await dashboardItem.click();
  await page.waitForURL(`${frontendUrl}/dashboard`);
  const sidebarNavigationShot = await savePage('sidebar-navigation-200-playwright.png');
  addCase('sidebar-navigation-200', 'PASS', { destination: page.url(), screenshot: sidebarNavigationShot });
  await page.goto(targetUrl, { waitUntil: 'domcontentloaded' });

  const createButton = page.getByRole('button', { name: '建立手動草稿' });
  await createButton.focus();
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog', { name: '建立手動採購單草稿' });
  await dialog.waitFor({ state: 'visible' });
  const dialogCheck = await dialog.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom,
      width: rect.width, height: rect.height, viewportWidth: innerWidth, viewportHeight: innerHeight,
      withinHorizontalViewport: rect.left >= 0 && rect.right <= innerWidth };
  });
  assert(dialogCheck.withinHorizontalViewport, 'PO form modal exceeds horizontal viewport at 200%');
  const firstFocus = await page.evaluate(() => !!document.activeElement?.closest('[role="dialog"]'));
  await page.keyboard.press('Escape');
  await dialog.waitFor({ state: 'hidden' });
  const focusReturned = await createButton.evaluate((element) => document.activeElement === element);
  assert(firstFocus && focusReturned, 'PO modal focus did not move in and return after Escape at 200%');
  addCase('po-modal-open-escape-focus-200', 'PASS', { dialog: dialogCheck, focusInsideOnOpen: firstFocus, focusReturned });

  await createButton.click();
  const formDialog = page.getByRole('dialog', { name: '建立手動採購單草稿' });
  const supplierBox = formDialog.getByRole('combobox', { name: '選取供應商' });
  await supplierBox.focus();
  await supplierBox.fill('SUP-120');
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: 'SUP-120' }).waitFor({ state: 'visible' });
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  const materialBox = formDialog.getByRole('combobox', { name: '選取物料' });
  await materialBox.focus();
  await materialBox.fill('MAT-101');
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: 'MAT-101 — Copper Wire' }).waitFor({ state: 'visible' });
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await formDialog.getByRole('textbox', { name: '採購單品項 1 數量' }).fill('12');
  await formDialog.getByRole('textbox', { name: '採購單品項 1 單價' }).fill('4.2500');
  await formDialog.getByRole('button', { name: /新增品項/ }).click();
  const secondMaterial = formDialog.getByRole('combobox', { name: '選取物料' }).nth(1);
  await secondMaterial.focus();
  await secondMaterial.fill('MAT-102');
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: 'MAT-102' }).waitFor({ state: 'visible' });
  await page.keyboard.press('ArrowDown');
  await page.keyboard.press('Enter');
  await formDialog.getByRole('textbox', { name: '採購單品項 2 數量' }).fill('5');
  await formDialog.getByRole('textbox', { name: '採購單品項 2 單價' }).fill('1.2500');
  const body = page.locator('.ant-modal-body').last();
  await body.evaluate((element) => { element.scrollTop = element.scrollHeight; });
  const bodyScroll = await body.evaluate((element) => ({ scrollTop: element.scrollTop, scrollHeight: element.scrollHeight,
    clientHeight: element.clientHeight, reachedBottom: element.scrollTop + element.clientHeight >= element.scrollHeight - 1 }));
  const reviewButton = formDialog.getByRole('button', { name: '檢視新草稿' });
  const reviewVisible = await reviewButton.isVisible();
  await savePage('po-form-200-playwright.png');
  assert(bodyScroll.reachedBottom && reviewVisible, 'PO form vertical scroll could not reach the review action');
  await reviewButton.click();
  const confirmDialog = page.getByRole('dialog', { name: '確認建立手動草稿' });
  await confirmDialog.waitFor({ state: 'visible' });
  const confirmGeometry = await confirmDialog.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, width: rect.width, height: rect.height,
      viewportWidth: innerWidth, viewportHeight: innerHeight,
      withinHorizontalViewport: rect.left >= 0 && rect.right <= innerWidth };
  });
  const confirmButton = confirmDialog.getByRole('button', { name: '確認建立草稿' });
  const confirmVisible = await confirmButton.isVisible();
  assert(confirmGeometry.withinHorizontalViewport && confirmVisible, 'PO confirmation dialog/action is not readable or operable');
  const confirmShot = await savePage('po-confirmation-200-playwright.png');
  await page.keyboard.press('Escape');
  await confirmDialog.waitFor({ state: 'hidden' });
  const confirmationFocusReturned = await reviewButton.evaluate((element) => document.activeElement === element);
  assert(confirmationFocusReturned, 'PO confirmation modal did not return focus after Escape');
  await reviewButton.click();
  await page.getByRole('dialog', { name: '確認建立手動草稿' }).getByRole('button', { name: '確認建立草稿' }).click();
  await page.getByText(/採購單 PO-UI-D-701 已建立|草稿已建立|已建立/).waitFor({ timeout: 10_000 });
  const correctIds = result.poDraftPayload?.supplierId === 120
    && result.poDraftPayload?.items?.[0]?.materialId === 101
    && result.poDraftPayload?.items?.[1]?.materialId === 102;
  assert(correctIds, 'Fixture PO payload did not preserve Supplier 120, Material 101 and Material 102 IDs');
  addCase('po-create-select-scroll-confirm-200', 'PASS', {
    supplierId: result.poDraftPayload.supplierId,
    materialIds: result.poDraftPayload.items.map((item) => item.materialId),
    supplierLabel: `${suppliers[119].code} — ${suppliers[119].name}`,
    materialLabel: `${materials[100].code} — ${materials[100].name}`,
    itemCount: result.poDraftPayload.items.length,
    bodyScroll,
    confirmGeometry,
    confirmVisible,
    confirmationFocusReturned,
    confirmScreenshot: confirmShot,
    payload: result.poDraftPayload,
  });

  await page.goto(`${frontendUrl}/documents`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('button', { name: '#501' }).click();
  const documentDrawer = page.locator('.ant-drawer-content-wrapper').filter({ has: page.getByText('文件明細', { exact: true }) });
  await documentDrawer.getByText(longTitle, { exact: true }).first().waitFor({ state: 'visible' });
  await page.waitForTimeout(450);
  const documentGeometry = await documentDrawer.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { left: rect.left, right: rect.right, width: rect.width, viewportWidth: innerWidth,
      withinViewport: rect.left >= 0 && rect.right <= innerWidth };
  });
  const documentShot = await savePage('document-drawer-200-playwright.png');
  addCase('document-drawer-200', documentGeometry.withinViewport ? 'PASS' : 'FAIL', {
    geometry: documentGeometry, longTitleVisible: true, screenshot: documentShot,
  });

  await page.goto(`${frontendUrl}/dashboard`, { waitUntil: 'domcontentloaded' });
  const assistantButton = page.getByRole('button', { name: /AI 助理/ });
  assert(await assistantButton.isVisible(), 'AI Assistant button is not visible at 200%');
  await assistantButton.click();
  const aiDrawer = page.locator('.ant-drawer-content-wrapper').filter({ has: page.getByText('SupplyPilot AI 助理', { exact: true }) });
  await aiDrawer.waitFor({ state: 'visible' });
  await page.waitForTimeout(450);
  const aiGeometry = await aiDrawer.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { left: rect.left, right: rect.right, width: rect.width, viewportWidth: innerWidth,
      withinViewport: rect.left >= 0 && rect.right <= innerWidth };
  });
  const aiShot = await savePage('ai-drawer-200-playwright.png');
  await page.keyboard.press('Escape');
  await aiDrawer.waitFor({ state: 'hidden' });
  const aiFocusReturned = await assistantButton.evaluate((element) => document.activeElement === element);
  addCase('ai-drawer-200', aiGeometry.withinViewport && aiFocusReturned ? 'PASS' : 'FAIL', {
    geometry: aiGeometry, screenshot: aiShot, closedWithEscape: true, focusReturned: aiFocusReturned,
  });

  await page.goto(`${frontendUrl}/purchase-orders`, { waitUntil: 'domcontentloaded' });
  const table = page.locator('.ant-table-content');
  await table.waitFor({ state: 'visible' });
  const tableBefore = await table.evaluate((element) => ({ clientWidth: element.clientWidth, scrollWidth: element.scrollWidth,
    scrollHeight: element.scrollHeight, clientHeight: element.clientHeight, overflowX: getComputedStyle(element).overflowX }));
  await table.evaluate((element) => { element.scrollLeft = element.scrollWidth; });
  const tableAfter = await table.evaluate((element) => ({ scrollLeft: element.scrollLeft,
    reachedHorizontalEnd: element.scrollWidth <= element.clientWidth || element.scrollLeft > 0 }));
  const listShot = await savePage('po-list-200-playwright.png');
  result.finalPage = await metrics();
  addCase('po-list-scroll-200', tableAfter.reachedHorizontalEnd ? 'PASS' : 'FAIL', { before: tableBefore, after: tableAfter, screenshot: listShot,
    pageVerticalScroll: { clientHeight: result.finalPage.document.clientHeight, scrollHeight: result.finalPage.document.scrollHeight,
      canScrollVertically: result.finalPage.document.scrollHeight > result.finalPage.document.clientHeight } });
  result.requestsSummary = requests.reduce((summary, request) => {
    const key = `${request.method} ${request.path}`;
    summary[key] = (summary[key] ?? 0) + 1;
    return summary;
  }, {});
  result.status = result.cases.every((testCase) => testCase.status === 'PASS') && pageErrors.length === 0 ? 'PASS' : 'FAIL';
  result.completedAt = now();
  await writeJson(resultPath, result);
} catch (error) {
  result.status = 'FAIL';
  result.error = error?.stack ?? String(error);
  result.completedAt = now();
  await writeJson(resultPath, result);
  process.exitCode = 1;
} finally {
  // A CDP connection is attached to a user-visible Edge session. End the Node
  // client only; Browser.close() would terminate the real Edge window.
  if (!cdpEndpoint && browser) await browser.close();
  else if (!browser) await context.close();
}
