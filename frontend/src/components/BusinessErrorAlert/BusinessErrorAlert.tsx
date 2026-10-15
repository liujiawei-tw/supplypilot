import { Alert, Button, Space, Typography } from 'antd';
import { classifyApiError } from '../../api/apiError';
import type { ApiErrorResponse } from '../../types/api';

const { Text } = Typography;

export interface BusinessErrorAlertProps {
  error: ApiErrorResponse;
  title?: string;
  message?: string;
  operation?: 'read' | 'mutation';
  onRetry?: () => void;
  retryText?: string;
}

export default function BusinessErrorAlert({
  error,
  title,
  message,
  operation = 'read',
  onRetry,
  retryText = '重新讀取',
}: BusinessErrorAlertProps) {
  const presentation = classifyApiError(error, operation);
  const allowRetry = !!onRetry && presentation.retryAllowed;
  return <Alert
    type={presentation.severity}
    showIcon
    title={title ?? presentation.title}
    data-error-kind={presentation.kind}
    description={<Space orientation="vertical" size={8} style={{ width: '100%' }}>
      <div>{message ?? presentation.message}</div>
      {(error.code || error.traceId || error.fieldErrors?.length) && <div style={{ fontSize: 12 }}>
        {error.code && <div><Text type="secondary">錯誤代碼： </Text><Text code>{error.code}</Text></div>}
        {error.traceId && <div><Text type="secondary">追蹤 ID： </Text><Text code>{error.traceId}</Text></div>}
        {error.fieldErrors?.map((fieldError) => <div key={`${fieldError.field}-${fieldError.message}`}>
          <Text type="secondary">{fieldError.field}: </Text><Text>{fieldError.message}</Text>
        </div>)}
      </div>}
      {allowRetry && <div><Button size="small" type="primary" onClick={onRetry}>{retryText}</Button></div>}
    </Space>}
    style={{ margin: '16px 0' }}
  />;
}
