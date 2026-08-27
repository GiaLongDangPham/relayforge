import { AppShell } from './app/AppShell'
import { PageFrame } from './app/PageFrame'
import styles from './app/app.module.css'
import { LoginScreen } from './features/auth/LoginScreen'
import { useAuthSession } from './features/auth/useAuthSession'
import { ProjectWorkspace } from './features/projects/ProjectWorkspace'

function App() {
  const session = useAuthSession()

  if (session.state.kind === 'checking') {
    return <PageFrame><p className={styles.statusMessage}>Checking your session…</p></PageFrame>
  }

  if (session.state.kind === 'unavailable') {
    return (
      <PageFrame>
        <section className={`${styles.panel} ${styles.statusPanel}`} aria-live="polite">
          <h1>RelayForge is unavailable</h1>
          <p>Check that the API is running, then refresh this page.</p>
          <button type="button" onClick={() => window.location.reload()}>Refresh</button>
        </section>
      </PageFrame>
    )
  }

  if (session.state.kind === 'anonymous') {
    return <LoginScreen onLogin={session.login} />
  }

  return (
    <AppShell owner={session.state.owner} onLogout={session.logout}>
      <ProjectWorkspace />
    </AppShell>
  )
}

export default App
