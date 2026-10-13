import axiosClient from './axiosClient';

export type DecimalValue = string | number;
export type RecommendationOutcome =
  | 'PURCHASE_NOT_REQUIRED'
  | 'SUPPLIER_RECOMMENDED'
  | 'NO_QUALIFIED_SUPPLIER';

export type DisqualificationReason =
  | 'SUPPLIER_INACTIVE'
  | 'SUPPLIER_MATERIAL_NOT_FOUND'
  | 'SUPPLIER_MATERIAL_INACTIVE'
  | 'DELIVERY_DATE_NOT_FEASIBLE'
  | string;

export interface CreateRecommendationRequest {
  materialId: number;
  periodStart: string;
  periodEnd: string;
}

export interface RecommendationResponse {
  id: number;
  material: { id: number; code: string; name: string; unit: string };
  period: { start: string; end: string };
  calculation: {
    expectedDemand: DecimalValue;
    safetyStock: DecimalValue;
    currentInventory: DecimalValue;
    incomingInventory: DecimalValue;
    netRequirement: DecimalValue;
  };
  outcome: RecommendationOutcome;
  recommendation: {
    purchaseRequired: boolean;
    supplierId: number | null;
    supplierName: string | null;
    quantity: DecimalValue | null;
    score: DecimalValue | null;
  };
  createdAt: string;
}

export interface SupplierRecommendationResultResponse {
  id: number;
  supplierId: number;
  supplierCode: string;
  supplierName: string;
  qualified: boolean;
  disqualifiedReason: DisqualificationReason | null;
  recommendedQuantity: DecimalValue | null;
  unitPrice: DecimalValue | null;
  totalCost: DecimalValue | null;
  leadTimeDays: number | null;
  reliabilityScoreSnapshot: DecimalValue | null;
  qualityScoreSnapshot: DecimalValue | null;
  priceScore: DecimalValue | null;
  leadTimeScore: DecimalValue | null;
  reliabilityScore: DecimalValue | null;
  qualityScore: DecimalValue | null;
  totalScore: DecimalValue | null;
  rank: number | null;
}

const decimalFields = [
  'expectedDemand', 'safetyStock', 'currentInventory', 'incomingInventory', 'netRequirement',
  'quantity', 'score', 'recommendedQuantity', 'unitPrice', 'totalCost',
  'reliabilityScoreSnapshot', 'qualityScoreSnapshot', 'priceScore', 'leadTimeScore',
  'reliabilityScore', 'qualityScore', 'totalScore',
] as const;
const decimalFieldSet = new Set<string>(decimalFields);
const decimalTokenPattern = new RegExp(
  `("(?:${decimalFields.join('|')})"\\s*:\\s*)(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)`,
  'g',
);

function normalizeDecimalFields(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(normalizeDecimalFields);
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(Object.entries(value).map(([key, nested]) => [
    key,
    decimalFieldSet.has(key) && typeof nested === 'number' ? String(nested) : normalizeDecimalFields(nested),
  ]));
}

// Axios normally uses JSON.parse, which can round a Java BigDecimal before React sees it.
// Quote only the documented decimal fields while the response is still raw JSON; identifiers
// and non-decimal fields retain their normal JSON types.
export function parseRecommendationResponse(data: unknown): unknown {
  if (typeof data !== 'string') return normalizeDecimalFields(data);
  if (!data.trim()) return data;
  return JSON.parse(data.replace(decimalTokenPattern, '$1"$2"'));
}

const transformResponse = [parseRecommendationResponse];

export async function createRecommendation(request: CreateRecommendationRequest): Promise<RecommendationResponse> {
  return (await axiosClient.post<RecommendationResponse>('/recommendations', request, { transformResponse })).data;
}

export async function getRecommendation(id: number): Promise<RecommendationResponse> {
  return (await axiosClient.get<RecommendationResponse>(`/recommendations/${id}`, { transformResponse })).data;
}

export async function getSupplierRanking(id: number): Promise<SupplierRecommendationResultResponse[]> {
  return (await axiosClient.get<SupplierRecommendationResultResponse[]>(`/recommendations/${id}/suppliers`, { transformResponse })).data;
}
