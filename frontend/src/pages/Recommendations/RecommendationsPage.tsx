import { useRef, useState } from 'react';
import { Button, Card, Form, Input, Space, Typography } from 'antd';
import { useNavigate } from 'react-router-dom';
import { createRecommendation, getSupplierRanking, type RecommendationResponse, type SupplierRecommendationResultResponse } from '../../api/recommendation';
import { toApiError } from '../../api/apiError';
import RelatedEntitySelect from '../../components/RelatedEntitySelect';
import ErrorState from '../../components/ErrorState';
import type { ApiErrorResponse } from '../../types/api';
import RecommendationResult from './RecommendationResult';

interface CreateValues {
  materialId?: number;
  periodStart?: string;
  periodEnd?: string;
}

interface LookupValues { recommendationId?: string }

export default function RecommendationsPage() {
  const navigate = useNavigate();
  const [createForm] = Form.useForm<CreateValues>();
  const [lookupForm] = Form.useForm<LookupValues>();
  const [recommendation, setRecommendation] = useState<RecommendationResponse | null>(null);
  const [ranking, setRanking] = useState<SupplierRecommendationResultResponse[] | null>(null);
  const [createError, setCreateError] = useState<ApiErrorResponse | null>(null);
  const [rankingError, setRankingError] = useState<ApiErrorResponse | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [rankingLoading, setRankingLoading] = useState(false);
  const rankingRequestId = useRef(0);

  const loadRanking = async (id: number) => {
    const current = ++rankingRequestId.current;
    setRankingLoading(true);
    setRankingError(null);
    try {
      const response = await getSupplierRanking(id);
      if (current === rankingRequestId.current) setRanking(response);
    } catch (error) {
      if (current === rankingRequestId.current) setRankingError(toApiError(error));
    } finally {
      if (current === rankingRequestId.current) setRankingLoading(false);
    }
  };

  const submit = async () => {
    if (submitting) return;
    let values: CreateValues;
    try { values = await createForm.validateFields(); } catch { return; }
    setSubmitting(true);
    setCreateError(null);
    setRecommendation(null);
    setRanking(null);
    rankingRequestId.current += 1;
    try {
      const response = await createRecommendation({ materialId: values.materialId!, periodStart: values.periodStart!, periodEnd: values.periodEnd! });
      setRecommendation(response);
      void loadRanking(response.id);
    } catch (error) {
      setCreateError(toApiError(error));
    } finally {
      setSubmitting(false);
    }
  };

  const lookup = async () => {
    let values: LookupValues;
    try { values = await lookupForm.validateFields(); } catch { return; }
    navigate(`/recommendations/${values.recommendationId}`);
  };

  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    <div><Typography.Title level={2} style={{ marginBottom: 0 }}>採購建議工作區</Typography.Title><Typography.Text type="secondary">建立新的採購建議，或依 Recommendation ID 查詢已儲存的決策快照。</Typography.Text></div>
    <Space align="start" size="large" wrap style={{ width: '100%' }}>
      <Card title="建立採購建議" style={{ flex: '1 1 520px' }}>
        {createError && <ErrorState title="建立採購建議失敗" error={createError} />}
        <Form form={createForm} layout="vertical">
          <Form.Item name="materialId" label="啟用中的物料" rules={[{ required: true, message: '請選擇物料' }]}><RelatedEntitySelect entity="material" activeOnly /></Form.Item>
          <Space align="start" wrap>
            <Form.Item name="periodStart" label="期間起日" rules={[{ required: true, message: '請選擇期間起日' }]}><Input aria-label="建議期間起日" type="date" /></Form.Item>
            <Form.Item name="periodEnd" label="期間迄日" dependencies={['periodStart']} rules={[
              { required: true, message: '請選擇期間迄日' },
              ({ getFieldValue }) => ({ validator(_, value: string | undefined) { const start = getFieldValue('periodStart') as string | undefined; return !value || !start || start <= value ? Promise.resolve() : Promise.reject(new Error('期間起日不可晚於迄日')); } }),
            ]}><Input aria-label="建議期間迄日" type="date" /></Form.Item>
          </Space>
          <Button type="primary" loading={submitting} disabled={submitting} onClick={() => void submit()}>計算採購建議</Button>
        </Form>
      </Card>
      <Card title="查詢既有採購建議" style={{ flex: '1 1 320px' }}>
        <Form form={lookupForm} layout="vertical">
          <Form.Item name="recommendationId" label="採購建議編號" rules={[
            { required: true, message: '請輸入採購建議編號' },
            { pattern: /^[1-9]\d*$/, message: '請輸入正整數格式的採購建議編號' },
          ]}><Input aria-label="採購建議編號" inputMode="numeric" /></Form.Item>
          <Button onClick={() => void lookup()}>查詢建議</Button>
        </Form>
      </Card>
    </Space>
    {recommendation && <RecommendationResult recommendation={recommendation} ranking={ranking} rankingLoading={rankingLoading} rankingError={rankingError} retryRanking={() => void loadRanking(recommendation.id)} showDeepLink />}
  </Space>;
}
