import { Navigate } from 'react-router-dom'
import { LoginScreen } from '../../features/auth/LoginScreen'
import { useAuthSession } from '../../features/auth/useAuthSession'
import { SessionChecking, SessionUnavailable } from './SessionRouteState'

export function LoginRoute() {
  const session = useAuthSession()

  if (session.state.kind === 'checking') {
    return <SessionChecking />
  }

  if (session.state.kind === 'unavailable') {
    return <SessionUnavailable />
  }

  if (session.state.kind === 'authenticated') {
    return <Navigate replace to="/app" />
  }

  return <LoginScreen onLogin={session.login} />
}
