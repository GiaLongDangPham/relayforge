import type { ReactNode } from 'react'
import styles from './asyncState.module.css'

type StateCopy = {
  detail?: ReactNode
  title: string
}

export function LoadingState({ label }: { label: string }) {
  return <p aria-live="polite" className={styles.loading} role="status">{label}</p>
}

export function EmptyState({ actionLabel, detail, onAction, title }: StateCopy & { actionLabel?: string; onAction?: () => void }) {
  return <div className={styles.empty}><strong>{title}</strong>{detail ? <p>{detail}</p> : null}{onAction && actionLabel ? <button onClick={onAction} type="button">{actionLabel}</button> : null}</div>
}

export function ReadErrorState({ detail, onRetry, retrying = false, title }: StateCopy & { onRetry?: () => void; retrying?: boolean }) {
  return (
    <div className={styles.error} role="alert">
      <div><strong>{title}</strong>{detail ? <p>{detail}</p> : null}</div>
      {onRetry ? <button disabled={retrying} onClick={onRetry} type="button">{retrying ? 'Retrying…' : 'Try again'}</button> : null}
    </div>
  )
}

export function ActionResult({ children }: { children: ReactNode }) {
  return <div aria-atomic="true" className={children ? styles.result : styles.silentStatus} role="status">{children}</div>
}
