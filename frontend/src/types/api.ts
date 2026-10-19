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
  /** Client-side metadata. The backend contract is unchanged. */
  httpStatus?: number;
  transportError?: boolean;
  timedOut?: boolean;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
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
  mustChangePassword?: boolean;
}

export type UserStatus = 'ACTIVE' | 'INACTIVE';

export interface UserRecord {
  id: number;
  username: string;
  displayName: string;
  role: UserRole;
  status: UserStatus;
  createdAt: string;
  updatedAt: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: 'Bearer';
  user: AuthenticatedUser;
}
