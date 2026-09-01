import { Navigate } from 'react-router-dom'
import { useAuthSession } from '../../features/auth/useAuthSession'
import { ProjectWorkspace } from '../../features/projects/ProjectWorkspace'
import { AppShell } from '../AppShell'
import { SessionChecking, SessionUnavailable } from './SessionRouteState'

export function PrivateDashboardRoute() {
  const session = useAuthSession()

  if (session.state.kind === 'checking') {
    return <SessionChecking />
  }

  if (session.state.kind === 'unavailable') {
    return <SessionUnavailable />
  }

  if (session.state.kind === 'anonymous') {
    return <Navigate replace to="/login" />
  }

  return (
    <AppShell owner={session.state.owner} onLogout={session.logout}>
      <ProjectWorkspace />
    </AppShell>
  )
}
