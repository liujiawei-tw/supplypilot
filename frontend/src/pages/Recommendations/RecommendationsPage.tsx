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
    <div><Typography.Title level={2} style={{ marginBottom: 0 }}>Recommendation Workstation</Typography.Title><Typography.Text type="secondary">建立單次採購決策，或以已知 Recommendation ID 查詢已保存的 Snapshot。</Typography.Text></div>
    <Space align="start" size="large" wrap style={{ width: '100%' }}>
      <Card title="Create Recommendation" style={{ flex: '1 1 520px' }}>
        {createError && <ErrorState title="Recommendation create failed" error={createError} />}
        <Form form={createForm} layout="vertical">
          <Form.Item name="materialId" label="Active Material" rules={[{ required: true, message: 'Material is required' }]}><RelatedEntitySelect entity="material" activeOnly /></Form.Item>
          <Space align="start" wrap>
            <Form.Item name="periodStart" label="Period Start" rules={[{ required: true, message: 'Period start is required' }]}><Input aria-label="Recommendation period start" type="date" /></Form.Item>
            <Form.Item name="periodEnd" label="Period End" dependencies={['periodStart']} rules={[
              { required: true, message: 'Period end is required' },
              ({ getFieldValue }) => ({ validator(_, value: string | undefined) { const start = getFieldValue('periodStart') as string | undefined; return !value || !start || start <= value ? Promise.resolve() : Promise.reject(new Error('Period start must not be later than period end')); } }),
            ]}><Input aria-label="Recommendation period end" type="date" /></Form.Item>
          </Space>
          <Button type="primary" loading={submitting} disabled={submitting} onClick={() => void submit()}>Calculate Recommendation</Button>
        </Form>
      </Card>
      <Card title="Open Known Recommendation" style={{ flex: '1 1 320px' }}>
        <Form form={lookupForm} layout="vertical">
          <Form.Item name="recommendationId" label="Recommendation ID" rules={[
            { required: true, message: 'Recommendation ID is required' },
            { pattern: /^[1-9]\d*$/, message: 'Enter a positive Recommendation ID' },
          ]}><Input aria-label="Recommendation ID" inputMode="numeric" /></Form.Item>
          <Button onClick={() => void lookup()}>Query Recommendation</Button>
        </Form>
      </Card>
    </Space>
    {recommendation && <RecommendationResult recommendation={recommendation} ranking={ranking} rankingLoading={rankingLoading} rankingError={rankingError} retryRanking={() => void loadRanking(recommendation.id)} showDeepLink />}
  </Space>;
}
