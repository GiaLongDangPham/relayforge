import { type ReactNode, useId, useState } from 'react'
import styles from './infoTip.module.css'

type InfoTipProps = {
  children: ReactNode
  label: string
}

/** Optional context only. Never hide a required action, warning, or validation rule here. */
export function InfoTip({ children, label }: InfoTipProps) {
  const id = useId()
  const [pinned, setPinned] = useState(false)

  return (
    <span className={styles.tip} data-pinned={pinned || undefined}>
      <button
        aria-describedby={id}
        aria-label={`More information about ${label}`}
        className={styles.trigger}
        onBlur={() => setPinned(false)}
        onClick={() => setPinned(current => !current)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') {
            setPinned(false)
            event.currentTarget.blur()
          }
        }}
        type="button"
      >
        <span aria-hidden="true">i</span>
      </button>
      <span className={styles.content} id={id} role="tooltip">{children}</span>
    </span>
  )
}
