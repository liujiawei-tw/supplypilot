import type { UserRole } from '../types/api';

export const roleLabels: Record<UserRole, string> = {
  PRODUCTION_PLANNER: 'Production Planner',
  PROCUREMENT_STAFF: 'Procurement Staff',
  PROCUREMENT_MANAGER: 'Procurement Manager',
  WAREHOUSE_STAFF: 'Warehouse Staff',
  ADMIN: 'Administrator',
};

// Mirrors security-design.md and the backend service-level @PreAuthorize rules.
export const permissions = {
  materials: ['PRODUCTION_PLANNER', 'PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'WAREHOUSE_STAFF', 'ADMIN'],
  suppliers: ['PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN'],
  demands: ['PRODUCTION_PLANNER', 'PROCUREMENT_MANAGER', 'ADMIN'],
  inventoryLevels: ['PRODUCTION_PLANNER', 'PROCUREMENT_STAFF', 'WAREHOUSE_STAFF', 'ADMIN'],
  stockTransactions: ['WAREHOUSE_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN'],
  inventoryImport: ['WAREHOUSE_STAFF', 'ADMIN'],
  recommendations: ['PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN'],
  recommendationRead: ['PRODUCTION_PLANNER', 'PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'WAREHOUSE_STAFF', 'ADMIN'],
  purchaseOrders: ['PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN'],
  receiving: ['WAREHOUSE_STAFF', 'ADMIN'],
  documents: ['PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN'],
  aiQuery: ['PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN'],
  aiProposal: ['PROCUREMENT_STAFF', 'ADMIN'],
} as const satisfies Record<string, readonly UserRole[]>;

export type Permission = keyof typeof permissions | 'inventory';

export function can(role: UserRole, permission: Permission): boolean {
  if (permission === 'inventory') {
    return can(role, 'inventoryLevels') || can(role, 'stockTransactions');
  }
  return (permissions[permission] as readonly UserRole[]).includes(role);
}
