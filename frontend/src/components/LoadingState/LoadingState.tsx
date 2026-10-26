import React from 'react';
import { Spin, Skeleton } from 'antd';

export interface LoadingStateProps {
  tip?: string;
  skeleton?: boolean;
  rows?: number;
  fullPage?: boolean;
}

export const LoadingState: React.FC<LoadingStateProps> = ({
  tip = '載入中…',
  skeleton = false,
  rows = 4,
  fullPage = false,
}) => {
  if (skeleton) {
    return (
        <div className="sp-loading-skeleton" data-testid="loading-skeleton">
        <Skeleton active paragraph={{ rows }} />
      </div>
    );
  }

  return (
    <div className={`sp-loading-state${fullPage ? ' sp-loading-state--full-page' : ''}`} data-testid="loading-spin" role="status" aria-live="polite">
      <Spin size="large" description={tip}>
        <div style={{ padding: '24px' }} />
      </Spin>
    </div>
  );
};

export default LoadingState;
