import React from 'react';
import { Alert, Result, Button, Space, Typography } from 'antd';
import type { ApiErrorResponse } from '../../types/api';

const { Text } = Typography;

export interface ErrorStateProps {
  title?: string;
  message?: string;
  error?: ApiErrorResponse | Error | null;
  onRetry?: () => void;
  retryText?: string;
  asResult?: boolean;
}

export const ErrorState: React.FC<ErrorStateProps> = ({
  title = 'An error occurred',
  message,
  error,
  onRetry,
  retryText = 'Retry',
  asResult = false,
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

  displayMessage = displayMessage || 'Something went wrong. Please try again later.';

  const extraDetails = (errorCode || traceId) && (
    <div style={{ marginTop: '8px', fontSize: '12px' }}>
      {errorCode && (
        <div>
          <Text type="secondary">Error Code: </Text>
          <Text code>{errorCode}</Text>
        </div>
      )}
      {traceId && (
        <div>
          <Text type="secondary">Trace ID: </Text>
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
        status="error"
        title={title}
        subTitle={
          <Space orientation="vertical" size={4}>
            <div>{displayMessage}</div>
            {extraDetails}
          </Space>
        }
        extra={
          onRetry ? (
            <Button type="primary" onClick={onRetry}>
              {retryText}
            </Button>
          ) : undefined
        }
      />
    );
  }

  return (
    <Alert
      type="error"
      showIcon
      title={title}
      description={
        <Space orientation="vertical" size={8} style={{ width: '100%' }}>
          <div>{displayMessage}</div>
          {extraDetails}
          {onRetry && (
            <div>
              <Button size="small" type="primary" danger onClick={onRetry}>
                {retryText}
              </Button>
            </div>
          )}
        </Space>
      }
      style={{ margin: '16px 0' }}
    />
  );
};

export default ErrorState;
