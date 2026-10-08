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
