import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, useLocation } from 'react-router-dom';
import QuickFinder from '../src/components/QuickFinder/QuickFinder';

const mocks = vi.hoisted(() => ({
  listPurchaseOrders: vi.fn(),
  listSuppliers: vi.fn(),
  listMaterials: vi.fn(),
  listInventory: vi.fn(),
  user: null as unknown,
}));

vi.mock('../src/auth/useAuth', () => ({
  useAuth: () => ({ user: mocks.user, logout: vi.fn() }),
}));
vi.mock('../src/api/purchaseOrder', () => ({ listPurchaseOrders: mocks.listPurchaseOrders }));
vi.mock('../src/api/supplier', () => ({ listSuppliers: mocks.listSuppliers }));
vi.mock('../src/api/material', () => ({ listMaterials: mocks.listMaterials }));
vi.mock('../src/api/inventory', () => ({ listInventory: mocks.listInventory }));

function page(content: unknown[] = []) {
  return { content, page: 0, size: 6, totalElements: content.length, totalPages: content.length ? 1 : 0 };
}

function LocationText() {
  const location = useLocation();
  return <output data-testid="current-location">{location.pathname + location.search}</output>;
}

function renderFinder() {
  return render(
    <MemoryRouter initialEntries={['/']}>
      <LocationText />
      <QuickFinder open onClose={vi.fn()} />
    </MemoryRouter>,
  );
}

describe('QuickFinder', () => {
  beforeEach(() => {
    mocks.user = { id: 1, username: 'buyer', displayName: '採購人員', role: 'PROCUREMENT_STAFF', canManageUsers: false };
    mocks.listPurchaseOrders.mockReset().mockResolvedValue(page());
    mocks.listSuppliers.mockReset().mockResolvedValue(page());
    mocks.listMaterials.mockReset().mockResolvedValue(page());
    mocks.listInventory.mockReset().mockResolvedValue(page());
  });

  afterEach(() => cleanup());

  it('waits for a completed search before showing an empty result', async () => {
    renderFinder();
    fireEvent.change(screen.getByRole('textbox', { name: '採購單查詢文字' }), { target: { value: 'PO-NOT-FOUND' } });
    expect(screen.queryByText('沒有找到符合條件的資料')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '查找' }));
    expect(await screen.findByText('沒有找到符合條件的資料')).toBeTruthy();
  });
  it('searches PO across supported server fields and opens the selected PO', async () => {
    const po = {
      id: 44,
      poNumber: 'PO-2026-0007',
      supplierCode: 'SUP-10',
      supplierName: '海川金屬',
      status: 'ORDERED',
      stage: 'ORDER_FOLLOW_UP',
      items: [{ id: 1 }],
      isOverdue: true,
      expectedDeliveryDate: '2026-10-27',
      supplierPromisedDeliveryDate: '2026-10-28',
    };
    mocks.listPurchaseOrders.mockImplementation(async (query: Record<string, unknown>) =>
      page(query.poNumber ? [po] : []),
    );

    renderFinder();
    fireEvent.change(screen.getByRole('textbox', { name: '採購單查詢文字' }), { target: { value: 'PO-2026-0007' } });
    fireEvent.click(screen.getByRole('button', { name: '查找' }));

    expect(await screen.findByText('PO-2026-0007')).toBeTruthy();
    expect(mocks.listPurchaseOrders).toHaveBeenCalledTimes(4);
    expect(mocks.listPurchaseOrders).toHaveBeenCalledWith({ poNumber: 'PO-2026-0007', page: 0, size: 6 });
    expect(mocks.listPurchaseOrders).toHaveBeenCalledWith({ supplierCode: 'PO-2026-0007', page: 0, size: 6 });
    expect(screen.getByText(/公司期望：2026\/10\/27/)).toBeTruthy();
    expect(screen.getByText(/供應承諾：2026\/10\/28/)).toBeTruthy();

    fireEvent.click(screen.getByRole('button', { name: '開啟' }));
    await waitFor(() => expect(screen.getByTestId('current-location').textContent).toBe('/purchase-orders/44'));
  });

  it('limits warehouse search to inventory and materials instead of calling the PO API', async () => {
    mocks.user = { id: 2, username: 'warehouse', displayName: '倉管', role: 'WAREHOUSE_STAFF', canManageUsers: false };

    renderFinder();
    fireEvent.change(screen.getByRole('textbox', { name: '物料查詢文字' }), { target: { value: 'MAT-200' } });
    fireEvent.click(screen.getByRole('button', { name: '查找' }));

    await waitFor(() => expect(mocks.listMaterials).toHaveBeenCalledWith({ code: 'MAT-200', page: 0, size: 6 }));
    expect(mocks.listMaterials).toHaveBeenCalledTimes(2);
    expect(mocks.listPurchaseOrders).not.toHaveBeenCalled();
  });

  it('preserves the matched supplier field when opening the paginated supplier list', async () => {
    const supplier = {
      id: 12,
      code: 'SUP-012',
      name: '東港材料行',
      country: '台灣',
      reliabilityScore: 0,
      qualityScore: 0,
      status: 'ACTIVE',
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
    };
    mocks.listSuppliers.mockImplementation(async (query: Record<string, unknown>) =>
      page(query.name ? [supplier] : []),
    );

    renderFinder();
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '查找範圍' }));
    fireEvent.click(await screen.findByText('供應商', { selector: '.ant-select-item-option-content' }));
    fireEvent.change(screen.getByRole('textbox', { name: '供應商查詢文字' }), { target: { value: '東港' } });
    fireEvent.click(screen.getByRole('button', { name: '查找' }));

    expect(await screen.findByText('SUP-012')).toBeTruthy();
    expect(mocks.listSuppliers).toHaveBeenCalledWith({ code: '東港', page: 0, size: 6 });
    expect(mocks.listSuppliers).toHaveBeenCalledWith({ name: '東港', page: 0, size: 6 });

    fireEvent.click(screen.getByRole('button', { name: '開啟' }));
    await waitFor(() => expect(screen.getByTestId('current-location').textContent)
      .toBe('/suppliers?finder=%E6%9D%B1%E6%B8%AF&tab=suppliers&finderField=name'));
  });
});

