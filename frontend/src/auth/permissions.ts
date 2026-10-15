import type { UserRole } from '../types/api';

export const roleLabels: Record<UserRole, string> = {
  PRODUCTION_PLANNER: '生產規劃人員',
  PROCUREMENT_STAFF: '採購人員',
  PROCUREMENT_MANAGER: '採購經理',
  WAREHOUSE_STAFF: '倉庫人員',
  ADMIN: '系統管理員',
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
  purchaseOrderRead: ['PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN'],
  purchaseOrderCreate: ['PROCUREMENT_STAFF', 'ADMIN'],
  purchaseOrderEdit: ['PROCUREMENT_STAFF', 'ADMIN'],
  purchaseOrderApproveReject: ['PROCUREMENT_MANAGER', 'ADMIN'],
  purchaseOrderOrder: ['PROCUREMENT_STAFF', 'ADMIN'],
  purchaseOrderDraft: ['PROCUREMENT_STAFF', 'ADMIN'],
  receiving: ['WAREHOUSE_STAFF', 'ADMIN'],
  documentRead: ['PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN'],
  documentUpload: ['PROCUREMENT_STAFF', 'ADMIN'],
  documentUpdate: ['PROCUREMENT_STAFF', 'ADMIN'],
  documentIngest: ['PROCUREMENT_STAFF', 'ADMIN'],
  semanticManagement: ['ADMIN'],
  aiQuery: ['PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN'],
  aiProposal: ['PROCUREMENT_STAFF', 'ADMIN'],
} as const satisfies Record<string, readonly UserRole[]>;

export type Permission = keyof typeof permissions | 'inventory' | 'documents';

export function can(role: UserRole, permission: Permission): boolean {
  if (permission === 'inventory') {
    return can(role, 'inventoryLevels') || can(role, 'stockTransactions');
  }
  if (permission === 'documents') return can(role, 'documentRead');
  return (permissions[permission] as readonly UserRole[]).includes(role);
}
