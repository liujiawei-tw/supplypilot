import { Alert, Button, Space } from 'antd';

export interface VerificationRequiredAlertProps {
  title: string;
  message: string;
  state?: 'required' | 'checking' | 'failed' | 'verified';
  onVerify?: () => void;
  verifyText?: string;
}

export default function VerificationRequiredAlert({
  title,
  message,
  state = 'required',
  onVerify,
  verifyText = '查詢 Backend 真實狀態',
}: VerificationRequiredAlertProps) {
  const type = state === 'verified' ? 'success' : state === 'failed' ? 'error' : 'warning';
  return <Alert
    type={type}
    showIcon
    title={title}
    description={<Space orientation="vertical" size={8}>
      <span>{message}</span>
      {onVerify && state !== 'verified' && <Button loading={state === 'checking'} disabled={state === 'checking'} onClick={onVerify}>{verifyText}</Button>}
    </Space>}
  />;
}
