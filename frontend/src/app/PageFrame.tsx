import type { ReactNode } from 'react'
import styles from './app.module.css'

export function PageFrame({ children, compact = false }: { children: ReactNode; compact?: boolean }) {
  return <div className={`${styles.pageFrame} ${compact ? styles.compactFrame : ''}`}>{children}</div>
}
