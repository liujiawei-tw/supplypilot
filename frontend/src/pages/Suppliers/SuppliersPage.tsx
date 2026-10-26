import { Tabs, Typography } from 'antd';
import { useSearchParams } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import SupplierList from './SupplierList';
import SupplierMaterialList from './SupplierMaterialList';

type SupplierTab = 'suppliers' | 'supplier-materials';

export default function SuppliersPage() {
  const { user } = useAuth();
  const [params, setParams] = useSearchParams();
  if (!user) return null;

  const isAdmin = user.role === 'ADMIN';
  const requestedTab = params.get('tab');
  const activeTab: SupplierTab = requestedTab === 'supplier-materials' ? 'supplier-materials' : 'suppliers';
  const finder = activeTab === 'suppliers' ? params.get('finder') ?? '' : '';
  const finderField = params.get('finderField') === 'name' ? 'name' : 'code';

  return <section aria-labelledby="suppliers-page-title" style={{ width: '100%' }}>
    <div style={{ borderBottom: '1px solid var(--sp-line)', paddingBottom: 16, marginBottom: 8 }}>
      <Typography.Title id="suppliers-page-title" level={2} style={{ margin: 0 }}>供應商</Typography.Title>
      <Typography.Text type="secondary">查找供應商資料，並檢視其供應物料條件。</Typography.Text>
    </div>
    <Tabs
      destroyOnHidden
      activeKey={activeTab}
      onChange={(key) => {
        const next = new URLSearchParams(params);
        next.set('tab', key);
        setParams(next);
      }}
      items={[
        { key: 'suppliers', label: '供應商目錄', children: <SupplierList isAdmin={isAdmin} finder={finder} finderField={finderField} /> },
        { key: 'supplier-materials', label: '供應商物料', children: <SupplierMaterialList isAdmin={isAdmin} /> },
      ]}
    />
  </section>;
}
