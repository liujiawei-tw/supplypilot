import { createBrowserRouter, Navigate } from 'react-router-dom';
import RequireAuth from '../auth/RequireAuth';
import RoleGuard from '../auth/RoleGuard';
import AppLayout from '../layouts/AppLayout';
import DashboardPage from '../pages/Dashboard/DashboardPage';
import DemandsPage from '../pages/Demands/DemandsPage';
import InventoryPage from '../pages/Inventory/InventoryPage';
import InventoryImportPage from '../pages/InventoryImport/InventoryImportPage';
import MaterialsPage from '../pages/Materials/MaterialsPage';
import RecommendationsPage from '../pages/Recommendations/RecommendationsPage';
import RecommendationDetailPage from '../pages/Recommendations/RecommendationDetailPage';
import SuppliersPage from '../pages/Suppliers/SuppliersPage';
import LoginPage from '../pages/Login/LoginPage';
import NotFoundPage from '../pages/NotFound/NotFoundPage';
import PlaceholderPage from '../pages/Placeholder/PlaceholderPage';
import PurchaseOrdersPage from '../pages/PurchaseOrders/PurchaseOrdersPage';
import PurchaseOrderDetailPage from '../pages/PurchaseOrders/PurchaseOrderDetailPage';
import ReceivingPage from '../pages/Receiving/ReceivingPage';
import ReceivingDetailPage from '../pages/Receiving/ReceivingDetailPage';
import DocumentsPage from '../pages/Documents/DocumentsPage';
import UsersPage from '../pages/Users/UsersPage';
import ChangePasswordPage from '../pages/ChangePassword/ChangePasswordPage';
import { canUser } from '../auth/permissions';
import { useAuth } from '../auth/useAuth';
import type { AuthenticatedUser } from '../types/api';
import { routeCatalog } from './routeCatalog';

export function defaultWorkPath(user: AuthenticatedUser): string {
  if (canUser(user, 'purchaseOrderRead')) return '/purchase-orders';
  if (canUser(user, 'receiving')) return '/receiving/purchase-orders';
  if (canUser(user, 'inventory')) return '/inventory';
  return '/dashboard';
}

function DefaultWorkRoute() {
  const { user } = useAuth();
  return <Navigate to={user ? defaultWorkPath(user) : '/dashboard'} replace />;
}

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <DefaultWorkRoute /> },
          { path: 'dashboard', element: <DashboardPage /> },
          { path: 'change-password', element: <ChangePasswordPage /> },
          ...routeCatalog.map(({ path, label, permission }) => ({
            element: <RoleGuard permission={permission} />,
            children: [{
              path,
              element: path === 'inventory'
                ? <InventoryPage />
                : path === 'inventory/import'
                  ? <InventoryImportPage />
                  : path === 'demands'
                    ? <DemandsPage />
                    : path === 'materials'
                      ? <MaterialsPage />
                      : path === 'recommendations'
                        ? <RecommendationsPage />
                        : path === 'recommendations/:id'
                          ? <RecommendationDetailPage />
                          : path === 'suppliers'
                            ? <SuppliersPage />
                            : path === 'purchase-orders'
                              ? <PurchaseOrdersPage />
                              : path === 'purchase-orders/:id'
                                ? <PurchaseOrderDetailPage />
                                : path === 'receiving/purchase-orders'
                                  ? <ReceivingPage />
                                  : path === 'receiving/purchase-orders/:id'
                                    ? <ReceivingDetailPage />
                                    : path === 'documents'
                                      ? <DocumentsPage />
                                      : path === 'users'
                                        ? <UsersPage />
                                        : <PlaceholderPage title={label} />,
            }],
          })),
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]);

export default router;
