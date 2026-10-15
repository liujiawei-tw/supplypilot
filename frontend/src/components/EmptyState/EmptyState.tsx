import React from 'react';
import { Empty, Button } from 'antd';

export interface EmptyStateProps {
  description?: React.ReactNode;
  actionText?: string;
  onAction?: () => void;
  extra?: React.ReactNode;
  simple?: boolean;
}

export const EmptyState: React.FC<EmptyStateProps> = ({
  description = '目前沒有資料',
  actionText,
  onAction,
  extra,
  simple = false,
}) => {
  return (
    <div style={{ padding: '32px 0' }} data-testid="empty-state">
      <Empty
        image={simple ? Empty.PRESENTED_IMAGE_SIMPLE : Empty.PRESENTED_IMAGE_DEFAULT}
        description={description}
      >
        {extra}
        {actionText && onAction && (
          <Button type="primary" onClick={onAction} style={{ marginTop: '8px' }}>
            {actionText}
          </Button>
        )}
      </Empty>
    </div>
  );
};

export default EmptyState;
