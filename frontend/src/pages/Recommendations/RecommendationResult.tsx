import { Button, Card, Space, Typography } from 'antd';
import type { ApiErrorResponse } from '../../types/api';
import type { RecommendationResponse, SupplierRecommendationResultResponse } from '../../api/recommendation';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import RecommendationSummary from '../../components/RecommendationSummary';
import RecommendationDraftForm from '../../components/RecommendationDraftForm';
import SupplierRankingTable from '../../components/SupplierRankingTable';

interface RecommendationResultProps {
  recommendation: RecommendationResponse;
  ranking: SupplierRecommendationResultResponse[] | null;
  rankingLoading: boolean;
  rankingError: ApiErrorResponse | null;
  retryRanking: () => void;
  showDeepLink?: boolean;
}

export default function RecommendationResult({ recommendation, ranking, rankingLoading, rankingError, retryRanking, showDeepLink }: RecommendationResultProps) {
  return <Space orientation="vertical" size="large" style={{ width: '100%' }}>
    <RecommendationSummary recommendation={recommendation} showDeepLink={showDeepLink} />
    <Card title="供應商排名快照" extra={<Typography.Text type="secondary">保留後端排序與名次</Typography.Text>}>
      {rankingError && <ErrorState title="供應商排名查詢失敗" error={rankingError} onRetry={retryRanking} retryText="重新查詢供應商排名" />}
      {rankingLoading && ranking === null ? <LoadingState tip="正在載入供應商排名…" /> : ranking !== null && <SupplierRankingTable rows={ranking} unit={recommendation.material.unit} />}
      {rankingError && ranking !== null && <Button onClick={retryRanking}>重新查詢供應商排名</Button>}
    </Card>
    {ranking !== null && <RecommendationDraftForm key={recommendation.id} recommendation={recommendation} ranking={ranking} />}
  </Space>;
}
