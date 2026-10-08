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
