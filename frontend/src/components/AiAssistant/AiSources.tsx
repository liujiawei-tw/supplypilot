import { Card, Descriptions, Space, Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';
import type { AiSourceResponse } from '../../api/ai';

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
  if (sources.length === 0) return <Text type="secondary">Backend 未提供可核對來源。</Text>;
  return <Space orientation="vertical" style={{ width: '100%' }}>
    {sources.map((source, index) => {
      const link = sourceLink(source);
      return <Card key={`${source.resourceType}-${source.resourceId}-${index}`} size="small"
        title={<Space wrap><Tag>{source.resourceType}</Tag><Text>{source.title}</Text></Space>}>
        <Descriptions size="small" column={1} items={[
          { key: 'id', label: 'Resource ID', children: link ? <Link to={link}>{source.resourceId}</Link> : source.resourceId },
          ...(source.version ? [{ key: 'version', label: 'Document Version', children: source.version }] : []),
          ...(source.supplierId !== null ? [{ key: 'supplier', label: 'Supplier ID', children: source.supplierId }] : []),
          ...(source.chunkIndex !== null ? [{ key: 'chunk', label: 'Chunk', children: source.chunkIndex }] : []),
          ...(source.startOffset !== null || source.endOffset !== null ? [{ key: 'offset', label: 'Offset', children: `${source.startOffset ?? '—'}–${source.endOffset ?? '—'}` }] : []),
          ...(source.relevanceScore !== null ? [{ key: 'score', label: 'Relevance Score', children: String(source.relevanceScore) }] : []),
        ]} />
        {source.excerpt && <Paragraph style={{ marginTop: 12, whiteSpace: 'pre-wrap' }}><Text strong>Relevant Excerpt</Text><br />{source.excerpt}</Paragraph>}
      </Card>;
    })}
  </Space>;
}
