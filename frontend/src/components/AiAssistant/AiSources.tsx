import { Card, Descriptions, Space, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';
import type { AiSourceResponse } from '../../api/ai';
import { displayLabel } from '../../utils/displayLabels';

const { Paragraph, Text } = Typography;

function safePositiveId(value: string): number | null {
  if (!/^\d+$/.test(value)) return null;
  const id = Number(value);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

function sourceLink(source: AiSourceResponse) {
  const id = safePositiveId(source.resourceId);
  if (!id) return null;
  if (source.resourceType === 'DOCUMENT') return `/documents?documentId=${id}`;
  if (source.resourceType === 'RECOMMENDATION' || source.resourceType === 'PURCHASE_RECOMMENDATION') return `/recommendations/${id}`;
  if (source.resourceType === 'PURCHASE_ORDER') return `/purchase-orders/${id}`;
  return null;
}

export default function AiSources({ sources }: { sources: AiSourceResponse[] }) {
  if (sources.length === 0) return <Text type="secondary">後端 未提供可核對來源。</Text>;
  return <Space orientation="vertical" style={{ width: '100%' }}>
    {sources.map((source, index) => {
      const link = sourceLink(source);
      return <Card key={`${source.resourceType}-${source.resourceId}-${index}`} size="small"
        title={<Space wrap><Tag>{displayLabel(source.resourceType)}</Tag><Text>{source.title}</Text></Space>}>
        <Descriptions size="small" column={1} items={[
          { key: 'id', label: '來源 ID', children: link ? <Link to={link}>{source.resourceId}</Link> : source.resourceId },
          ...(source.version ? [{ key: 'version', label: '文件版本', children: source.version }] : []),
          ...(source.supplierId !== null ? [{ key: 'supplier', label: '供應商 ID', children: source.supplierId }] : []),
          ...(source.chunkIndex !== null ? [{ key: 'chunk', label: '來源片段序號', children: source.chunkIndex }] : []),
          ...(source.startOffset !== null || source.endOffset !== null ? [{ key: 'offset', label: '原文位置', children: `${source.startOffset ?? '—'}–${source.endOffset ?? '—'}` }] : []),
          ...(source.relevanceScore !== null ? [{ key: 'score', label: '相關度分數', children: String(source.relevanceScore) }] : []),
        ]} />
        {source.excerpt && <Paragraph style={{ marginTop: 12, whiteSpace: 'pre-wrap' }}><Text strong>引用內容</Text><br />{source.excerpt}</Paragraph>}
      </Card>;
    })}
  </Space>;
}
