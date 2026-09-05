import { useState, type ReactNode } from 'react'
import type { OwnerIdentity } from '../api/apiClient'
import { PageFrame } from './PageFrame'
import styles from './app.module.css'

type AppShellProps = {
  owner: OwnerIdentity
  onLogout: () => Promise<void>
  children: ReactNode
}

export function AppShell({ owner, onLogout, children }: AppShellProps) {
  const [submitting, setSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function logout() {
    setSubmitting(true)
    setErrorMessage(null)
    try {
      await onLogout()
    } catch {
      setErrorMessage('Unable to sign out. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <PageFrame compact>
      <a className={styles.skipLink} href="#workspace">Skip to workspace</a>
      <header className={styles.appHeader}>
        <div>
          <h1>RelayForge</h1>
        </div>
        <div className={styles.ownerActions}>
          <span>{owner.loginName}</span>
          <div className={styles.logoutControl}>
            <button className={styles.secondaryButton} disabled={submitting} onClick={logout} type="button">
              {submitting ? 'Signing out…' : 'Sign out'}
            </button>
            {errorMessage ? <span className={styles.formError} role="alert">{errorMessage}</span> : null}
          </div>
        </div>
      </header>
      {children}
    </PageFrame>
  )
}
