import React from 'react';
import { Result, Button, Space, Typography } from 'antd';
import { classifyApiError } from '../../api/apiError';
import type { ApiErrorResponse } from '../../types/api';
import BusinessErrorAlert from '../BusinessErrorAlert';

const { Text } = Typography;

export interface ErrorStateProps {
  title?: string;
  message?: string;
  error?: ApiErrorResponse | Error | null;
  onRetry?: () => void;
  retryText?: string;
  asResult?: boolean;
  operation?: 'read' | 'mutation';
}

export const ErrorState: React.FC<ErrorStateProps> = ({
  title,
  message,
  error,
  onRetry,
  retryText = '重新載入',
  asResult = false,
  operation = 'read',
}) => {
  let displayMessage = message;
  let errorCode: string | undefined;
  let traceId: string | undefined;
  let fieldErrors: ApiErrorResponse['fieldErrors'];

  if (error) {
    if ('code' in error && 'message' in error) {
      const apiErr = error as ApiErrorResponse;
      displayMessage = displayMessage || apiErr.message;
      errorCode = apiErr.code;
      traceId = apiErr.traceId;
      fieldErrors = apiErr.fieldErrors;
    } else if (error instanceof Error) {
      displayMessage = displayMessage || error.message;
    }
  }

  displayMessage = displayMessage || '發生問題，請稍後再試。';

  const apiError = error && 'code' in error && 'message' in error
    ? error as ApiErrorResponse
    : null;
  const presentation = apiError ? classifyApiError(apiError, operation) : null;

  const extraDetails = (errorCode || traceId || fieldErrors?.length) && (
    <div style={{ marginTop: '8px', fontSize: '12px' }}>
      {errorCode && (
        <div>
          <Text type="secondary">錯誤代碼： </Text>
          <Text code>{errorCode}</Text>
        </div>
      )}
      {traceId && (
        <div>
          <Text type="secondary">追蹤 ID： </Text>
          <Text code>{traceId}</Text>
        </div>
      )}
      {fieldErrors?.map((fieldError) => (
        <div key={`${fieldError.field}-${fieldError.message}`}>
          <Text type="secondary">{fieldError.field}: </Text>
          <Text>{fieldError.message}</Text>
        </div>
      ))}
    </div>
  );

  if (asResult) {
    return (
      <Result
        className="sp-error-state"
        status="error"
        title={title ?? presentation?.title ?? '無法完成請求'}
        subTitle={
          <Space orientation="vertical" size={4}>
            <div>{displayMessage}</div>
            {extraDetails}
          </Space>
        }
        extra={
          onRetry && (presentation?.retryAllowed ?? true) ? (
            <Button type="primary" onClick={onRetry}>
              {retryText}
            </Button>
          ) : undefined
        }
      />
    );
  }

  if (apiError) return <BusinessErrorAlert error={apiError} title={title} message={message} operation={operation} onRetry={onRetry} retryText={retryText} />;

  return <BusinessErrorAlert error={{ code: 'REQUEST_FAILED', message: displayMessage, traceId: '', timestamp: '', path: '' }} title={title} message={displayMessage} operation={operation} onRetry={onRetry} retryText={retryText} />;
};

export default ErrorState;
