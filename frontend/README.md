# SupplyPilot — Frontend

Thin client shell for the SupplyPilot AI procurement and supply-chain decision platform.

## Technology Stack

- React 19
- TypeScript
- Vite 8
- Ant Design
- Axios
- React Router

## Local Setup

1. Install dependencies:

   ```powershell
   npm install
   ```

2. Copy environment file:

   ```powershell
   copy .env.example .env
   ```

3. Start development server:

   ```powershell
   npm run dev
   ```

4. Build for production:

   ```powershell
   npm run build
   ```

## Environment Variables

- `VITE_API_BASE_URL`: Base URL for the SupplyPilot backend API (default: `http://localhost:8080/api/v1`).

## Authentication

V1 stores the Bearer access token in `localStorage` and verifies it with `GET /auth/me` when the app starts. A production-grade deployment should evaluate an HttpOnly Secure Cookie and refresh token strategy. The Login page never displays a demo password.
