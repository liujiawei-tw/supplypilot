import { useRef, useState } from 'react';
import { Alert, Button, Input, Tag, Typography } from 'antd';
import { queryAi, type AiAnswerData, type AiQueryResponse } from '../../api/ai';
import { toApiError } from '../../api/apiError';
import ErrorState from '../ErrorState';
import AiSources from './AiSources';
import AiAnswerDisplay from './AiAnswerDisplay';
import type { ApiErrorResponse } from '../../types/api';
import { displayLabel } from '../../utils/displayLabels';
import './AiAssistant.css';

const { Text, Title } = Typography;

function queryErrorMessage(error: ApiErrorResponse): string {
  if (error.code === 'AI_PROVIDER_UNAVAILABLE') return 'AI 服務暫時無法使用。核心採購功能與導覽仍可正常操作。';
  if (error.code === 'AI_TIMEOUT') return 'AI Provider 回應逾時，系統沒有製造替代答案。你可以稍後重新提問。';
  if (error.code === 'AI_INVALID_RESPONSE') return 'AI Provider 回覆未通過後端驗證，因此沒有顯示該答案。';
  if (error.code === 'AI_QUERY_FAILED') return '資料查詢目前無法完成；系統未將查詢錯誤當成查無資料。';
  if (error.code === 'ACCESS_DENIED') return '你沒有執行 AI Query 的權限。';
  return error.message;
}

function hasAnswerDetails(details: AiAnswerData | undefined): boolean {
  return details != null && (details.inventory.length + details.quotes.length + details.averagePrices.length
    + details.suppliers.length + details.purchaseOrders.length + details.candidates.length
    + details.notes.length + details.unmatchedIdentifiers.length > 0 || details.purchaseOrderTotalCount != null);
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
    catch (requestError) {
      const apiError = toApiError(requestError);
      setError({ ...apiError, message: queryErrorMessage(apiError) });
    }
    finally { loadingRef.current = false; setLoading(false); }
  };

  return <section className="ai-query sp-section">
    <header className="ai-query__heading">
      <Title className="ai-query__title" level={4}>查詢採購資料與文件</Title>
      <Text className="ai-query__description">每次問題獨立查詢。核對回答所附的文件、版本與原文；沒有足夠證據時，系統會明確告知。</Text>
    </header>
    <div className="ai-query__composer">
      <Input.TextArea className="ai-query__input" aria-label="AI 問題" rows={3} maxLength={2000} showCount value={message}
        placeholder="輸入採購問題，或提供採購單號、供應商／物料代碼等識別資料。"
        onChange={(event) => setMessage(event.target.value)}
        onPressEnter={(event) => {
          if ((event.ctrlKey || event.metaKey) && !event.shiftKey) {
            event.preventDefault();
            void submit();
          }
        }} />
      <div className="ai-query__composer-footer">
        <Text type="secondary">Ctrl/⌘ + Enter 送出</Text>
        <Button className="ai-query__submit" type="primary" autoInsertSpace={false} loading={loading} disabled={!message.trim()} onClick={() => void submit()}>
          查詢
        </Button>
      </div>
    </div>
    {error && <ErrorState title="AI 問答失敗" error={error} onRetry={lastMessage ? () => void submit(lastMessage) : undefined} retryText="重新提問" />}
    {response?.status === 'NEEDS_CLARIFICATION' && <Alert type="warning" showIcon title="需要補充資料"
      description={response.answer || '系統無法辨識問題中的採購資料；請補上採購單號、供應商或物料識別資料後再查詢。'} />}
    {response?.status === 'NEEDS_CLARIFICATION' && response.details && response.details.candidates.length > 0 &&
      <section className="ai-query__result-section" aria-live="polite">
        <Title className="ai-query__result-title" level={5}>可選資料</Title>
        <AiAnswerDisplay answer={response.answer} details={response.details} summary={response.summary} />
      </section>}
    {response?.status === 'INSUFFICIENT_EVIDENCE' && <Alert type="info" showIcon title="證據不足"
      description="目前沒有足夠來源支持肯定答案，因此不會顯示推測內容。可補充明確的識別資料後再查詢。" />}
    {response?.status === 'INSUFFICIENT_EVIDENCE' && (response.answer || hasAnswerDetails(response.details)) &&
      <section className="ai-query__result-section" aria-live="polite">
        <Title className="ai-query__result-title" level={5}>部分查詢結果</Title>
        <AiAnswerDisplay answer={response.answer} details={response.details} summary={response.summary} />
        {response.tools.length > 0 && <div className="ai-query__tools" aria-label="使用的查詢方式">
          {response.tools.map((tool) => <Tag key={tool}>{displayLabel(tool)}</Tag>)}
        </div>}
      </section>}
    {response?.status === 'NOT_FOUND' && <Alert type="info" showIcon title="查無資料"
      description={response.answer || '系統已完成查詢，但目前沒有符合條件的業務資料。'} />}
    {response?.status === 'ANSWERED' && <section className="ai-query__result" aria-live="polite">
      <div className="ai-query__result-section">
        <Title className="ai-query__result-title" level={5}>回答</Title>
        {response.answer || response.details
          ? <AiAnswerDisplay answer={response.answer} details={response.details} summary={response.summary} />
          : <Text type="secondary">後端未提供可顯示回答。</Text>}
        {response.answerSource && <Text type="secondary">答案依據：{displayLabel(response.answerSource)}</Text>}
        {response.tools.length > 0 && <div className="ai-query__tools" aria-label="使用的查詢方式">
          {response.tools.map((tool) => <Tag key={tool}>{displayLabel(tool)}</Tag>)}
        </div>}
      </div>
    </section>}
    {response && <section className="ai-query__result-section" aria-live="polite">
      <Title className="ai-query__result-title" level={5}>資料來源</Title>
      <AiSources sources={response.sources} />
    </section>}
  </section>;
}