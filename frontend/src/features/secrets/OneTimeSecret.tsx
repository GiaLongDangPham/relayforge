import { useState } from 'react'
import styles from './oneTimeSecret.module.css'

type OneTimeSecretProps = {
  label: string
  value: string
  onClose: () => void
}

export function OneTimeSecret({ label, value, onClose }: OneTimeSecretProps) {
  const [copyMessage, setCopyMessage] = useState<string | null>(null)

  async function copy() {
    try {
      await navigator.clipboard.writeText(value)
      setCopyMessage('Copied. Store it securely now.')
    } catch {
      setCopyMessage('Clipboard access failed. Select and copy the value manually.')
    }
  }

  return (
    <section className={styles.reveal} aria-live="assertive">
      <h3>Save this {label} now</h3>
      <p>This is the only time RelayForge can show it.</p>
      <code className={styles.secretValue}>{value}</code>
      <div className={styles.actions}>
        <button onClick={() => void copy()} type="button">Copy</button>
        <button className={styles.closeButton} onClick={onClose} type="button">I saved it</button>
      </div>
      {copyMessage ? <p className={styles.copyMessage}>{copyMessage}</p> : null}
    </section>
  )
}
