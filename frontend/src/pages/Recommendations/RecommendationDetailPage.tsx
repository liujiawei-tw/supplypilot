import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Space, Tag, Typography } from 'antd';
import { Link, useParams } from 'react-router-dom';
import { getRecommendation, getSupplierRanking, type RecommendationResponse, type SupplierRecommendationResultResponse } from '../../api/recommendation';
import { toApiError } from '../../api/apiError';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import { useAuth } from '../../auth/useAuth';
import type { ApiErrorResponse } from '../../types/api';
import RecommendationResult from './RecommendationResult';
import './RecommendationDetailPage.css';

export default function RecommendationDetailPage() {
  const { user } = useAuth();
  const historyOnly = user?.role === 'ADMIN';
  const { id: rawId } = useParams();
  const id = rawId && /^[1-9]\d*$/.test(rawId) && Number.isSafeInteger(Number(rawId)) ? Number(rawId) : null;
  const [recommendation, setRecommendation] = useState<RecommendationResponse | null>(null);
  const [ranking, setRanking] = useState<SupplierRecommendationResultResponse[] | null>(null);
  const [detailError, setDetailError] = useState<ApiErrorResponse | null>(null);
  const [rankingError, setRankingError] = useState<ApiErrorResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(id !== null);
  const [rankingLoading, setRankingLoading] = useState(id !== null && !historyOnly);
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
    if (id === null || historyOnly) return;
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
  }, [historyOnly, id]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (id !== null) {
        void loadDetail();
        if (!historyOnly) void loadRanking();
      }
    }, 0);
    return () => {
      window.clearTimeout(timer);
      detailRequestId.current += 1;
      rankingRequestId.current += 1;
    };
  }, [historyOnly, id, loadDetail, loadRanking]);

  if (id === null) return <ErrorState asResult title="採購建議編號無效" message="請輸入正整數格式的採購建議編號。" />;

  return <section className="sp-page legacy-recommendation-detail" aria-labelledby="legacy-recommendation-detail-title">
    <header className="legacy-recommendation-detail__heading sp-page__heading">
      <div>
        <Space wrap>
          <Typography.Title id="legacy-recommendation-detail-title" level={2}>採購建議明細</Typography.Title>
          <Tag className="legacy-recommendation-detail__tag">舊版資料</Tag>
        </Space>
        <Typography.Text className="legacy-recommendation-detail__intro sp-page__lead">已儲存的決策快照 #{id}</Typography.Text>
      </div>
      <Link to={historyOnly ? '/purchase-orders' : '/recommendations'}>
        <Button>{historyOnly ? '返回採購單管理' : '返回採購建議'}</Button>
      </Link>
    </header>
    {detailLoading && !recommendation && <LoadingState tip="正在載入採購建議明細…" />}
    {detailError && <ErrorState asResult title={detailError.code === 'RECOMMENDATION_NOT_FOUND' ? '找不到採購建議' : '採購建議明細查詢失敗'} error={detailError} onRetry={() => void loadDetail()} retryText="重新查詢明細" />}
    {recommendation && <div className="legacy-recommendation-detail__result"><RecommendationResult recommendation={recommendation} ranking={ranking} rankingLoading={rankingLoading} rankingError={rankingError} retryRanking={() => void loadRanking()} historyOnly={historyOnly} /></div>}
  </section>;
}
