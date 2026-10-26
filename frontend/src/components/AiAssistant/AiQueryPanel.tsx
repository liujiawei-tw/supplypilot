import { useRef, useState } from 'react';
import { Alert, Button, Card, Input, Space, Tag, Typography } from 'antd';
import { queryAi, type AiQueryResponse } from '../../api/ai';
import { toApiError } from '../../api/apiError';
import ErrorState from '../ErrorState';
import AiSources from './AiSources';
import AiAnswerDisplay from './AiAnswerDisplay';
import type { ApiErrorResponse } from '../../types/api';
import { displayLabel } from '../../utils/displayLabels';

const { Text, Title } = Typography;

function queryErrorMessage(error: ApiErrorResponse): string {
  if (error.code === 'AI_PROVIDER_UNAVAILABLE') return 'AI 服務暫時無法使用。核心採購功能與導覽仍可正常操作。';
  if (error.code === 'AI_TIMEOUT') return 'AI Provider 回應逾時，系統沒有製造替代答案。你可以稍後重新提問。';
  if (error.code === 'AI_INVALID_RESPONSE') return 'AI Provider 回覆未通過 後端 驗證，未顯示不可信答案。';
  if (error.code === 'AI_QUERY_FAILED') return '資料查詢目前無法完成；系統未將查詢錯誤當成查無資料。';
  if (error.code === 'ACCESS_DENIED') return '你沒有執行 AI Query 的權限。';
  return error.message;
}

export default function AiQueryPanel() {
  const [message, setMessage] = useState('');
  const [lastMessage, setLastMessage] = useState('');
  const [response, setResponse] = useState<AiQueryResponse | null>(null);
  const [error, setError] = useState<ApiErrorResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const loadingRef = useRef(false);

  const submit = async (value = message) => {
    const trimmed = value.trim();
    if (!trimmed || trimmed.length > 2000 || loadingRef.current) return;
    loadingRef.current = true; setLoading(true); setError(null); setResponse(null); setLastMessage(trimmed);
    try { setResponse(await queryAi(trimmed)); }
    catch (requestError) { const apiError = toApiError(requestError); setError({ ...apiError, message: queryErrorMessage(apiError) }); }
    finally { loadingRef.current = false; setLoading(false); }
  };

  return <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
    <div><Title level={4} style={{ marginBottom: 0 }}>向 SupplyPilot 提問</Title><Text type="secondary">單輪同步查詢；不保留對話記憶，也不提供串流回應。</Text></div>
    <Input.TextArea aria-label="AI 問題" rows={4} maxLength={2000} showCount value={message}
      placeholder="例如：阿薩姆紅茶萃取物目前庫存？或：MAT-001 平均價格？"
      onChange={(event) => setMessage(event.target.value)} onPressEnter={(event) => { if (!event.shiftKey) { event.preventDefault(); void submit(); } }} />
    <Button type="primary" loading={loading} disabled={!message.trim()} onClick={() => void submit()}>送出問題</Button>
    {error && <ErrorState title="AI 問答失敗" error={error} onRetry={lastMessage ? () => void submit(lastMessage) : undefined} retryText="重新提問" />}
    {response?.status === 'NEEDS_CLARIFICATION' && <Alert type="warning" showIcon title="需要補充資料"
      description={response.answer || '後端 無法辨識必要的 Recommendation、Material、Supplier 或 PO 識別資料；請在新的完整問題中補充。'} />}
    {response?.status === 'INSUFFICIENT_EVIDENCE' && <Alert type="info" showIcon title="證據不足"
      description="後端 沒有足夠證據支持肯定答案，因此不會顯示推測內容。" />}
    {response?.status === 'INSUFFICIENT_EVIDENCE' && (response.answer || (response.details && (response.details.inventory.length > 0
      || response.details.quotes.length > 0 || response.details.averagePrices.length > 0 || response.details.suppliers.length > 0
      || response.details.purchaseOrders.length > 0 || response.details.candidates.length > 0
      || response.details.unmatchedIdentifiers.length > 0 || response.details.notes.length > 0
      || response.details.purchaseOrderTotalCount != null))) && <Card size="small" title="部分查詢結果">
      <AiAnswerDisplay answer={response.answer} details={response.details} summary={response.summary} />
      {response.tools.length > 0 && <div style={{ marginTop: 8 }}>{response.tools.map((tool) => <Tag key={tool}>{displayLabel(tool)}</Tag>)}</div>}
    </Card>}
    {response?.status === 'NOT_FOUND' && <Alert type="info" showIcon title="查無資料"
      description={response.answer || '系統已完成查詢，但目前沒有符合條件的業務資料。'} />}
    {response?.status === 'ANSWERED' && <Card size="small" title="回答">
      {response.answer || response.details
        ? <AiAnswerDisplay answer={response.answer} details={response.details} summary={response.summary} />
        : <Text type="secondary">後端未提供可顯示回答。</Text>}
      {response.answerSource && <Text type="secondary">答案來源：{displayLabel(response.answerSource)}</Text>}
      {response.tools.length > 0 && <div style={{ marginTop: 8 }}>{response.tools.map((tool) => <Tag key={tool}>{displayLabel(tool)}</Tag>)}</div>}
    </Card>}
    {response?.status === 'NEEDS_CLARIFICATION' && response.details && response.details.candidates.length > 0 && <Card size="small" title="可選資料">
      <AiAnswerDisplay answer={response.answer} details={response.details} summary={response.summary} />
    </Card>}
    {response && <Card size="small" title="資料來源"><AiSources sources={response.sources} /></Card>}
  </Space>;
}
