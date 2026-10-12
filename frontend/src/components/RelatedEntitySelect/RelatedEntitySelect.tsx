import { useCallback, useEffect, useRef, useState } from 'react';
import { Select, Spin } from 'antd';
import { listMaterials, type MaterialResponse } from '../../api/material';
import { listSuppliers, type SupplierResponse } from '../../api/supplier';

type Entity = Pick<MaterialResponse | SupplierResponse, 'id' | 'code' | 'name'>;

interface RelatedEntitySelectProps {
  entity: 'material' | 'supplier';
  value?: number;
  onChange?: (value: number) => void;
  disabled?: boolean;
  activeOnly?: boolean;
  onError?: (error: unknown) => void;
}

export default function RelatedEntitySelect({ entity, value, onChange, disabled, activeOnly = true, onError }: RelatedEntitySelectProps) {
  const [items, setItems] = useState<Entity[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(1);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(false);
  const requestId = useRef(0);

  const load = useCallback(async (nextPage: number, replace: boolean) => {
    const current = ++requestId.current;
    setLoading(true);
    try {
      const common = { page: nextPage, size: 20, sort: 'code,asc' };
      const response = entity === 'material'
        ? await listMaterials({ ...common, status: activeOnly ? 'ACTIVE' : undefined, code: search || undefined })
        : await listSuppliers({ ...common, status: activeOnly ? 'ACTIVE' : undefined, code: search || undefined });
      if (current !== requestId.current) return;
      setItems((existing) => replace ? response.content : [...existing, ...response.content.filter((candidate) => !existing.some((item) => item.id === candidate.id))]);
      setPage(response.page);
      setTotalPages(response.totalPages);
    } catch (error) {
      onError?.(error);
    } finally {
      if (current === requestId.current) setLoading(false);
    }
  }, [activeOnly, entity, onError, search]);

  useEffect(() => {
    const timer = window.setTimeout(() => void load(0, true), 0);
    return () => window.clearTimeout(timer);
  }, [load]);

  return <Select<number>
    showSearch filterOption={false} value={value} onChange={onChange} disabled={disabled}
    placeholder={`Select ${entity}`} notFoundContent={loading ? <Spin size="small" /> : 'No results'}
    options={items.map((item) => ({ value: item.id, label: `${item.code} — ${item.name}` }))}
    onSearch={setSearch}
    onPopupScroll={(event) => {
      const target = event.currentTarget;
      if (!loading && page + 1 < totalPages && target.scrollTop + target.offsetHeight >= target.scrollHeight - 8) void load(page + 1, false);
    }}
  />;
}
