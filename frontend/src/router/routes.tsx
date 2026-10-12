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
import { routeCatalog } from './routeCatalog';

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: <RequireAuth />,
    children: [
      {
        element: <AppLayout />,
        children: [
          { index: true, element: <Navigate to="/dashboard" replace /> },
          { path: 'dashboard', element: <DashboardPage /> },
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
