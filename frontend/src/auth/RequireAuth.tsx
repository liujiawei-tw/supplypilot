import { Navigate, Outlet, useLocation } from 'react-router-dom';
import LoadingState from '../components/LoadingState/LoadingState';
import { useAuth } from './useAuth';
import SessionVerificationError from './SessionVerificationError';

export default function RequireAuth() {
  const { isAuthenticated, isLoading, verificationError, user } = useAuth();
  const location = useLocation();
  if (isLoading) return <LoadingState />;
  if (verificationError) return <SessionVerificationError />;
  if (!isAuthenticated) return <Navigate to="/login" state={{ from: location }} replace />;
  if (user?.mustChangePassword && location.pathname !== '/change-password') return <Navigate to="/change-password" replace />;
  if (!user?.mustChangePassword && location.pathname === '/change-password') return <Navigate to="/dashboard" replace />;
  return <Outlet />;
}
