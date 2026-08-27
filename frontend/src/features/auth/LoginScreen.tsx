import { type SubmitEvent, useState } from 'react'
import { ApiProblem } from '../../api/apiClient'
import { PageFrame } from '../../app/PageFrame'
import appStyles from '../../app/app.module.css'
import styles from './auth.module.css'

type LoginScreenProps = {
  onLogin: (loginName: string, password: string) => Promise<void>
}

export function LoginScreen({ onLogin }: LoginScreenProps) {
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setErrorMessage(null)

    try {
      await onLogin(loginName, password)
    } catch (error: unknown) {
      setErrorMessage(loginErrorMessage(error))
    } finally {
      setPassword('')
      setSubmitting(false)
    }
  }

  return (
    <PageFrame>
      <main className={styles.loginLayout}>
        <section className={styles.loginIntroduction}>
          <p className={appStyles.eyebrow}>Webhook delivery platform</p>
          <h1>RelayForge</h1>
          <p>Inspect reliable outbound deliveries without exposing the credentials that protect them.</p>
        </section>
        <form className={`${appStyles.panel} ${styles.loginForm}`} onSubmit={submit}>
          <h2>Sign in</h2>
          <p className={styles.formDescription}>Use the bootstrap owner account configured for your local environment.</p>
          <label>
            Login name
            <input
              autoComplete="username"
              disabled={submitting}
              name="loginName"
              onChange={(event) => setLoginName(event.target.value)}
              required
              value={loginName}
            />
          </label>
          <label>
            Password
            <input
              autoComplete="current-password"
              disabled={submitting}
              name="password"
              onChange={(event) => setPassword(event.target.value)}
              required
              type="password"
              value={password}
            />
          </label>
          {errorMessage ? <p className={appStyles.formError} role="alert">{errorMessage}</p> : null}
          <button disabled={submitting} type="submit">
            {submitting ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </main>
    </PageFrame>
  )
}

function loginErrorMessage(error: unknown): string {
  if (error instanceof ApiProblem && error.status === 429) {
    return 'Too many failed attempts. Please try again later.'
  }
  return 'Unable to sign in with those credentials.'
}
