import { Tabs, Typography } from 'antd';
import { useSearchParams } from 'react-router-dom';
import { useAuth } from '../../auth/useAuth';
import SupplierList from './SupplierList';
import SupplierMaterialList from './SupplierMaterialList';
import './SuppliersPage.css';

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

  return <section className="sp-page supplier-workspace" aria-labelledby="suppliers-page-title">
    <header className="supplier-workspace__heading sp-page__heading">
      <div>
        <Typography.Title id="suppliers-page-title" level={2}>供應商</Typography.Title>
        <Typography.Text className="supplier-workspace__intro sp-page__lead">查找供應商資料，並檢視其供應物料條件。</Typography.Text>
      </div>
    </header>
    <Tabs
      className="supplier-workspace__tabs"
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
