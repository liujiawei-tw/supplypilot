import { can, canUser, type Permission } from '../auth/permissions';
import type { AuthenticatedUser, UserRole } from '../types/api';

export interface RouteEntry {
  path: string;
  label: string;
  group: string;
  permission: Permission;
  navigation?: boolean;
}

export const routeCatalog: RouteEntry[] = [
  { path: 'materials', label: '物料管理', group: '主檔管理', permission: 'materials', navigation: true },
  { path: 'suppliers', label: '供應商管理', group: '主檔管理', permission: 'suppliers', navigation: true },
  { path: 'demands', label: '需求管理', group: '需求與庫存', permission: 'demands', navigation: true },
  { path: 'inventory', label: '庫存管理', group: '需求與庫存', permission: 'inventory', navigation: true },
  { path: 'inventory/import', label: '庫存匯入', group: '需求與庫存', permission: 'inventoryImport', navigation: true },
  { path: 'recommendations', label: '採購建議', group: '採購決策', permission: 'recommendations', navigation: true },
  { path: 'recommendations/:id', label: '採購建議明細', group: '採購決策', permission: 'recommendationRead' },
  { path: 'purchase-orders', label: '採購單管理', group: '採購單與收貨', permission: 'purchaseOrderRead', navigation: true },
  { path: 'purchase-orders/:id', label: '採購單明細', group: '採購單與收貨', permission: 'purchaseOrderRead' },
  { path: 'receiving/purchase-orders', label: '收貨作業', group: '採購單與收貨', permission: 'receiving', navigation: true },
  { path: 'receiving/purchase-orders/:id', label: '收貨明細', group: '採購單與收貨', permission: 'receiving' },
  { path: 'documents', label: '文件管理', group: '文件與 AI', permission: 'documentRead', navigation: true },
  { path: 'users', label: '人員管理', group: '系統管理', permission: 'userManagement', navigation: true },
];

export function isAllowedDestination(pathname: string, user: AuthenticatedUser | UserRole): boolean {
  if (pathname === '/dashboard') return true;
  const entry = routeCatalog.find(({ path }) => {
    const pattern = new RegExp(`^/${path.replace(/:[^/]+/g, '[^/]+')}/?$`);
    return pattern.test(pathname);
  });
  return !!entry && (typeof user === 'string'
    ? entry.permission !== 'userManagement' && can(user, entry.permission)
    : canUser(user, entry.permission));
}
