import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Space, Typography } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { getRecommendation, getSupplierRanking, type RecommendationResponse, type SupplierRecommendationResultResponse } from '../../api/recommendation';
import { toApiError } from '../../api/apiError';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import type { ApiErrorResponse } from '../../types/api';
import RecommendationResult from './RecommendationResult';

export default function RecommendationDetailPage() {
  const { id: rawId } = useParams();
  const id = rawId && /^[1-9]\d*$/.test(rawId) && Number.isSafeInteger(Number(rawId)) ? Number(rawId) : null;
  const [recommendation, setRecommendation] = useState<RecommendationResponse | null>(null);
  const [ranking, setRanking] = useState<SupplierRecommendationResultResponse[] | null>(null);
  const [detailError, setDetailError] = useState<ApiErrorResponse | null>(null);
  const [rankingError, setRankingError] = useState<ApiErrorResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(id !== null);
  const [rankingLoading, setRankingLoading] = useState(id !== null);
  const detailRequestId = useRef(0);
  const rankingRequestId = useRef(0);

  const loadDetail = useCallback(async () => {
    if (id === null) return;
    const current = ++detailRequestId.current;
    setRecommendation(null);
    setDetailLoading(true);
    setDetailError(null);
    try {
      const response = await getRecommendation(id);
      if (current === detailRequestId.current) setRecommendation(response);
    } catch (error) {
      if (current === detailRequestId.current) setDetailError(toApiError(error));
    } finally {
      if (current === detailRequestId.current) setDetailLoading(false);
    }
  }, [id]);

  const loadRanking = useCallback(async () => {
    if (id === null) return;
    const current = ++rankingRequestId.current;
    setRanking(null);
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
  }, [id]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (id !== null) { void loadDetail(); void loadRanking(); }
    }, 0);
    return () => {
      window.clearTimeout(timer);
      detailRequestId.current += 1;
      rankingRequestId.current += 1;
    };
  }, [id, loadDetail, loadRanking]);

  if (id === null) return <ErrorState asResult title="Invalid Recommendation ID" message="Recommendation ID must be a positive safe integer." />;

  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
      <div><Typography.Title level={2} style={{ marginBottom: 0 }}>Recommendation Detail</Typography.Title><Typography.Text type="secondary">Persisted decision snapshot #{id}</Typography.Text></div>
      <Link to="/recommendations"><Button>Back to Workstation</Button></Link>
    </Space>
    {detailLoading && !recommendation && <LoadingState tip="Loading recommendation detail..." />}
    {detailError && <ErrorState asResult title={detailError.code === 'RECOMMENDATION_NOT_FOUND' ? 'Recommendation not found' : 'Recommendation detail query failed'} error={detailError} onRetry={() => void loadDetail()} retryText="Retry detail query" />}
    {recommendation && <RecommendationResult recommendation={recommendation} ranking={ranking} rankingLoading={rankingLoading} rankingError={rankingError} retryRanking={() => void loadRanking()} />}
  </Space>;
}
