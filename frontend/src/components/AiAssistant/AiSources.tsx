import { Tag, Typography } from 'antd';
import { Link } from 'react-router-dom';
import type { AiSourceResponse } from '../../api/ai';
import { displayLabel } from '../../utils/displayLabels';
import './AiAssistant.css';

const { Text } = Typography;

function safePositiveId(value: string): number | null {
  if (!/^\d+$/.test(value)) return null;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

function sourceLink(source: AiSourceResponse) {
  const id = safePositiveId(source.resourceId);
  if (id === null) return null;
  if (source.resourceType === 'RECOMMENDATION' || source.resourceType === 'PURCHASE_RECOMMENDATION') return '/recommendations/' + id;
  if (source.resourceType === 'PURCHASE_ORDER') return '/purchase-orders/' + id;
  if (source.resourceType === 'DOCUMENT') return '/documents?documentId=' + id;
  return null;
}

export default function AiSources({ sources }: { sources: AiSourceResponse[] }) {
  const uniqueSources = [...new Map(sources.map((source) => [
    [source.resourceType, source.resourceId, source.version ?? '', source.chunkIndex ?? ''].join(':'),
    source,
  ])).values()];
  if (uniqueSources.length === 0) return <Text type="secondary">後端未提供可核對來源。</Text>;
  return <div className="ai-sources" aria-label="AI 回答來源">
    {uniqueSources.map((source, index) => {
      const link = sourceLink(source);
      return <article className="ai-source" key={source.resourceType + '-' + source.resourceId + '-' + index}>
        <div className="ai-source__heading">
          <Tag>{displayLabel(source.resourceType)}</Tag>
          {link
            ? <Link className="ai-source__link ai-source__title" to={link}>{source.title}</Link>
            : <Text className="ai-source__title">{source.title}</Text>}
        </div>
        <dl className="ai-source__meta">
          <div className="ai-source__meta-item">
            <dt>來源 ID</dt>
            <dd>{link ? <Link className="ai-source__link" to={link}>{source.resourceId}</Link> : source.resourceId}</dd>
          </div>
          {source.version && <div className="ai-source__meta-item"><dt>文件版本</dt><dd>{source.version}</dd></div>}
          {source.documentKey && <div className="ai-source__meta-item"><dt>來源識別</dt><dd>{source.documentKey}</dd></div>}
          {source.supplierId !== null && <div className="ai-source__meta-item"><dt>供應商 ID</dt><dd>{source.supplierId}</dd></div>}
        </dl>
        <blockquote className="ai-source__excerpt">
          <span className="ai-source__excerpt-label">原文摘錄</span>
          {source.excerpt || '後端未提供原文摘錄，請開啟來源資料核對。'}
        </blockquote>
      </article>;
    })}
  </div>;
}