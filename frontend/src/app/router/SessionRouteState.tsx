import { PageFrame } from '../PageFrame'
import styles from '../app.module.css'

export function SessionChecking() {
  return <PageFrame><p className={styles.statusMessage}>Checking your session…</p></PageFrame>
}

export function SessionUnavailable() {
  return (
    <PageFrame>
      <main className={`${styles.panel} ${styles.statusPanel}`} aria-live="polite">
        <h1>RelayForge is unavailable</h1>
        <p>Check that the API is running, then refresh this page.</p>
        <button type="button" onClick={() => window.location.reload()}>Refresh</button>
      </main>
    </PageFrame>
  )
}
