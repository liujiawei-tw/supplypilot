import { Button, Card, Space, Typography } from 'antd';
import type { ApiErrorResponse } from '../../types/api';
import type { RecommendationResponse, SupplierRecommendationResultResponse } from '../../api/recommendation';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import RecommendationSummary from '../../components/RecommendationSummary';
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
    <Card title="Supplier Ranking Snapshot" extra={<Typography.Text type="secondary">Backend order and rank preserved</Typography.Text>}>
      {rankingError && <ErrorState title="Supplier ranking query failed" error={rankingError} onRetry={retryRanking} retryText="Retry supplier ranking" />}
      {rankingLoading && ranking === null ? <LoadingState tip="Loading supplier ranking..." /> : ranking !== null && <SupplierRankingTable rows={ranking} unit={recommendation.material.unit} />}
      {rankingError && ranking !== null && <Button onClick={retryRanking}>Retry supplier ranking</Button>}
    </Card>
  </Space>;
}
