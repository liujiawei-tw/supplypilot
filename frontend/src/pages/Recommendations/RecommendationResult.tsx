import { Collapse, Typography } from 'antd';
import type { ApiErrorResponse } from '../../types/api';
import type { RecommendationResponse, SupplierRecommendationResultResponse } from '../../api/recommendation';
import ErrorState from '../../components/ErrorState';
import LoadingState from '../../components/LoadingState';
import RecommendationSummary from '../../components/RecommendationSummary';
import RecommendationDraftForm from '../../components/RecommendationDraftForm';
import SupplierRankingTable from '../../components/SupplierRankingTable';
import './RecommendationResult.css';

interface RecommendationResultProps {
  recommendation: RecommendationResponse;
  ranking: SupplierRecommendationResultResponse[] | null;
  rankingLoading: boolean;
  rankingError: ApiErrorResponse | null;
  retryRanking: () => void;
  showDeepLink?: boolean;
}

export default function RecommendationResult({ recommendation, ranking, rankingLoading, rankingError, retryRanking, showDeepLink }: RecommendationResultProps) {
  const rankingState = rankingError ? 'error' : rankingLoading ? 'loading' : 'ready';
  return <div className="recommendation-result">
    <RecommendationSummary recommendation={recommendation} showDeepLink={showDeepLink} rankingState={rankingState} />

    {rankingError && <ErrorState
      title="供應商排名查詢失敗"
      error={rankingError}
      onRetry={retryRanking}
      retryText="重新查詢供應商排名"
    />}

    <div id="recommendation-ranking">
      <Collapse
        defaultActiveKey={recommendation.outcome === 'NO_QUALIFIED_SUPPLIER' ? ['ranking'] : []}
        items={[{
        key: 'ranking',
        label: '供應商排名與排除原因',
        extra: <Typography.Text type="secondary">保留 Backend 原始名次與排序</Typography.Text>,
        children: rankingLoading && ranking === null
          ? <LoadingState tip="正在載入供應商排名…" />
          : ranking !== null
            ? <SupplierRankingTable rows={ranking} unit={recommendation.material.unit} />
            : rankingError
              ? <Typography.Text type="secondary">排名暫時無法顯示；可使用上方按鈕重新查詢。</Typography.Text>
              : <Typography.Text type="secondary">尚未取得供應商排名。</Typography.Text>,
        }]}
      />
    </div>

    {ranking !== null && recommendation.outcome === 'SUPPLIER_RECOMMENDED' && <div id="recommendation-draft">
      <RecommendationDraftForm key={recommendation.id} recommendation={recommendation} ranking={ranking} />
    </div>}
  </div>;
}
