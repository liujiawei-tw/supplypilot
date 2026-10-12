import { Tabs, Typography } from 'antd';
import { useAuth } from '../../auth/useAuth';
import SupplierList from './SupplierList';
import SupplierMaterialList from './SupplierMaterialList';

export default function SuppliersPage() {
  const { user } = useAuth();
  if (!user) return null;
  const isAdmin = user.role === 'ADMIN';
  return <div>
    <Typography.Title level={2} style={{ marginBottom: 0 }}>Suppliers</Typography.Title>
    <Typography.Text type="secondary">供應商與供應物料關聯主檔</Typography.Text>
    <Tabs destroyOnHidden items={[
      { key: 'suppliers', label: 'Suppliers', children: <SupplierList isAdmin={isAdmin} /> },
      { key: 'supplier-materials', label: 'Supplier Materials', children: <SupplierMaterialList isAdmin={isAdmin} /> },
    ]} />
  </div>;
}
