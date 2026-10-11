import React from 'react';
import { ConfigProvider } from 'antd';
import { RouterProvider } from 'react-router-dom';
import router from './router/routes';
import { AuthProvider } from './auth/AuthContext';

export const App: React.FC = () => {
  return (
    <ConfigProvider
      theme={{
        token: {
          colorPrimary: '#1677ff',
          borderRadius: 6,
        },
      }}
    >
      <AuthProvider><RouterProvider router={router} /></AuthProvider>
    </ConfigProvider>
  );
};

export default App;
