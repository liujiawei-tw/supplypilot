import { Outlet } from 'react-router-dom';
import { useAuth } from './useAuth';
import { can, type Permission } from './permissions';
import UnauthorizedPage from '../pages/Unauthorized/UnauthorizedPage';

export default function RoleGuard({ permission }: { permission: Permission }) {
  const { user } = useAuth();
  if (!user || !can(user.role, permission)) return <UnauthorizedPage />;
  return <Outlet />;
}
