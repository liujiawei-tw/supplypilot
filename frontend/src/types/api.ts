export interface FieldError {
  field: string;
  rejectedValue?: unknown;
  message: string;
}

export interface ApiErrorResponse {
  code: string;
  message: string;
  traceId: string;
  timestamp: string;
  path: string;
  fieldErrors?: FieldError[];
}

export type UserRole =
  | 'PRODUCTION_PLANNER'
  | 'PROCUREMENT_STAFF'
  | 'PROCUREMENT_MANAGER'
  | 'WAREHOUSE_STAFF'
  | 'ADMIN';

export interface AuthenticatedUser {
  id: number;
  username: string;
  displayName: string;
  role: UserRole;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: 'Bearer';
  user: AuthenticatedUser;
}
