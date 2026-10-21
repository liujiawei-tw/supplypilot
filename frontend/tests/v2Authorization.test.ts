import { describe, expect, it } from 'vitest';
import { canUser } from '../src/auth/permissions';
import { isAllowedDestination } from '../src/router/routeCatalog';
import type { AuthenticatedUser } from '../src/types/api';

const account = (canManageUsers: boolean): AuthenticatedUser => ({
  id: 1,
  username: 'buyer',
  displayName: '採購帳號',
  role: 'ADMIN',
  canManageUsers,
});

describe('V2 account capability authorization', () => {
  it('allows every ADMIN business account to use procurement routes', () => {
    const businessAccount = account(false);
    expect(canUser(businessAccount, 'purchaseOrderCreate')).toBe(true);
    expect(isAllowedDestination('/purchase-orders', businessAccount)).toBe(true);
  });

  it('shows user-management routes only for accounts with the separate capability', () => {
    expect(canUser(account(false), 'userManagement')).toBe(false);
    expect(isAllowedDestination('/users', account(false))).toBe(false);
    expect(canUser(account(true), 'userManagement')).toBe(true);
    expect(isAllowedDestination('/users', account(true))).toBe(true);
  });
});
