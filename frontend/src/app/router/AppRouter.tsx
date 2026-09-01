import { Navigate, Route, Routes } from 'react-router-dom'
import { LandingPage } from '../../features/landing/LandingPage'
import { LoginRoute } from './LoginRoute'
import { PrivateDashboardRoute } from './PrivateDashboardRoute'

export function AppRouter() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route path="/login" element={<LoginRoute />} />
      <Route path="/app" element={<PrivateDashboardRoute />} />
      <Route path="*" element={<Navigate replace to="/" />} />
    </Routes>
  )
}
