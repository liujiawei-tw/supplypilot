import { Outlet } from 'react-router-dom';
import { useAuth } from './useAuth';
import { canUser, type Permission } from './permissions';
import UnauthorizedPage from '../pages/Unauthorized/UnauthorizedPage';

export default function RoleGuard({ permission }: { permission: Permission }) {
  const { user } = useAuth();
  if (!user || !canUser(user, permission)) return <UnauthorizedPage />;
  return <Outlet />;
}
