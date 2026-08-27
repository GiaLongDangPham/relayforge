import type { ReactNode } from 'react'
import styles from './app.module.css'

export function PageFrame({ children }: { children: ReactNode }) {
  return <div className={styles.pageFrame}>{children}</div>
}
