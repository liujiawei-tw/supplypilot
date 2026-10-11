import { can, type Permission } from '../auth/permissions';
import type { UserRole } from '../types/api';

export interface RouteEntry {
  path: string;
  label: string;
  group: string;
  permission: Permission;
  navigation?: boolean;
}

export const routeCatalog: RouteEntry[] = [
  { path: 'materials', label: 'Materials', group: 'Master Data', permission: 'materials', navigation: true },
  { path: 'suppliers', label: 'Suppliers', group: 'Master Data', permission: 'suppliers', navigation: true },
  { path: 'demands', label: 'Demands', group: 'Demand / Inventory', permission: 'demands', navigation: true },
  { path: 'inventory', label: 'Inventory', group: 'Demand / Inventory', permission: 'inventory', navigation: true },
  { path: 'inventory/import', label: 'Inventory Import', group: 'Demand / Inventory', permission: 'inventoryImport', navigation: true },
  { path: 'recommendations', label: 'Recommendations', group: 'Procurement Decisions', permission: 'recommendations', navigation: true },
  { path: 'recommendations/:id', label: 'Recommendation Detail', group: 'Procurement Decisions', permission: 'recommendationRead' },
  { path: 'purchase-orders', label: 'Purchase Orders', group: 'Purchase Orders / Receiving', permission: 'purchaseOrders', navigation: true },
  { path: 'purchase-orders/:id', label: 'Purchase Order Detail', group: 'Purchase Orders / Receiving', permission: 'purchaseOrders' },
  { path: 'receiving/purchase-orders', label: 'Receiving', group: 'Purchase Orders / Receiving', permission: 'receiving', navigation: true },
  { path: 'receiving/purchase-orders/:id', label: 'Receiving Detail', group: 'Purchase Orders / Receiving', permission: 'receiving' },
  { path: 'documents', label: 'Documents', group: 'Documents', permission: 'documents', navigation: true },
];

export function isAllowedDestination(pathname: string, role: UserRole): boolean {
  if (pathname === '/dashboard') return true;
  const entry = routeCatalog.find(({ path }) => {
    const pattern = new RegExp(`^/${path.replace(/:[^/]+/g, '[^/]+')}/?$`);
    return pattern.test(pathname);
  });
  return !!entry && can(role, entry.permission);
}
