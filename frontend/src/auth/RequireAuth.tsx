import { Navigate, Outlet, useLocation } from 'react-router-dom';
import LoadingState from '../components/LoadingState/LoadingState';
import { useAuth } from './useAuth';
import SessionVerificationError from './SessionVerificationError';

export default function RequireAuth() {
  const { isAuthenticated, isLoading, verificationError } = useAuth();
  const location = useLocation();
  if (isLoading) return <LoadingState />;
  if (verificationError) return <SessionVerificationError />;
  if (!isAuthenticated) return <Navigate to="/login" state={{ from: location }} replace />;
  return <Outlet />;
}
