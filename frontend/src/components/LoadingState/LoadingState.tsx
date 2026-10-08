import React from 'react';
import { Spin, Skeleton } from 'antd';

export interface LoadingStateProps {
  tip?: string;
  skeleton?: boolean;
  rows?: number;
  fullPage?: boolean;
}

export const LoadingState: React.FC<LoadingStateProps> = ({
  tip = 'Loading...',
  skeleton = false,
  rows = 4,
  fullPage = false,
}) => {
  if (skeleton) {
    return (
      <div style={{ padding: '24px 0' }} data-testid="loading-skeleton">
        <Skeleton active paragraph={{ rows }} />
      </div>
    );
  }

  const containerStyle: React.CSSProperties = fullPage
    ? {
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        minHeight: '60vh',
        width: '100%',
      }
    : {
        display: 'flex',
        justifyContent: 'center',
        alignItems: 'center',
        padding: '32px 0',
        width: '100%',
      };

  return (
    <div style={containerStyle} data-testid="loading-spin">
      <Spin size="large" tip={tip}>
        <div style={{ padding: '24px' }} />
      </Spin>
    </div>
  );
};

export default LoadingState;
