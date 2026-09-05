import { Link } from 'react-router-dom'
import { PageFrame } from '../../app/PageFrame'
import appStyles from '../../app/app.module.css'
import { DeliveryPathVisual } from './DeliveryPathVisual'
import styles from './landing.module.css'

const workflowSteps = [
  ['Publish', 'Send an event with a project API key.'],
  ['Persist', 'RelayForge saves the event before delivery begins.'],
  ['Dispatch', 'A worker sends signed webhooks to matching endpoints.'],
  ['Inspect', 'Review attempts and replay exhausted deliveries.'],
] as const

const capabilities = [
  ['Durable acceptance', 'Saved work survives an API or worker restart.'],
  ['Signed dispatch', 'Requests include an identifier, timestamp, and signature.'],
  ['Bounded recovery', 'Retries are limited and history stays available.'],
] as const

export function LandingPage() {
  return (
    <>
      <a className={styles.skipLink} href="#main-content">Skip to content</a>
      <PageFrame>
        <header className={styles.siteHeader}>
          <Link aria-label="RelayForge home" className={styles.brand} to="/">RelayForge</Link>
          <nav aria-label="Landing sections" className={styles.sectionNav}>
            <a href="#workflow">How it works</a>
            <a href="#reliability">Reliability</a>
            <a href="#architecture">Architecture</a>
          </nav>
          <Link className={styles.signInLink} to="/login">Sign in</Link>
        </header>

        <main id="main-content" tabIndex={-1}>
          <section aria-labelledby="landing-title" className={styles.hero}>
            <div className={styles.heroCopy}>
              <p className={appStyles.eyebrow}>Outbound webhook delivery platform</p>
              <h1 id="landing-title">Send webhooks you can track.</h1>
              <p className={styles.heroLead}>Accept events, deliver them reliably, and see every attempt.</p>
              <div className={styles.heroActions}>
                <Link className={styles.primaryAction} to="/login">Sign in to dashboard</Link>
                <a className={styles.secondaryAction} href="#workflow">See how delivery works</a>
              </div>
            </div>
            <DeliveryPathVisual />
          </section>

          <ul aria-label="RelayForge delivery foundations" className={styles.proofPoints}>
            <li><strong>Saved first</strong><span>Delivery begins after the event is recorded.</span></li>
            <li><strong>Your workspace</strong><span>See only your projects and delivery history.</span></li>
            <li><strong>Clear limits</strong><span>At-least-once delivery; no ordering guarantee.</span></li>
          </ul>

          <section aria-labelledby="workflow-heading" className={styles.contentSection} id="workflow">
            <div className={styles.sectionIntroduction}>
              <p className={appStyles.eyebrow}>One durable workflow</p>
              <h2 id="workflow-heading">From event to delivery</h2>
              <p>Publishing stays separate from receiver availability.</p>
            </div>
            <ol className={styles.workflowList}>
              {workflowSteps.map(([title, detail], index) => (
                <li key={title}>
                  <span aria-hidden="true" className={styles.stepNumber}>{index + 1}</span>
                  <div>
                    <h3>{title}</h3>
                    <p>{detail}</p>
                  </div>
                </li>
              ))}
            </ol>
          </section>

          <section aria-labelledby="reliability-heading" className={styles.contentSection} id="reliability">
            <div className={styles.sectionIntroduction}>
              <p className={appStyles.eyebrow}>Reliable by explicit limits</p>
              <h2 id="reliability-heading">Built for recoverable delivery</h2>
              <p>At-least-once delivery means receivers should tolerate duplicates.</p>
            </div>
            <div className={styles.capabilityGrid}>
              {capabilities.map(([title, detail]) => (
                <article className={styles.capability} key={title}>
                  <h3>{title}</h3>
                  <p>{detail}</p>
                </article>
              ))}
            </div>
          </section>

          <section aria-labelledby="architecture-heading" className={styles.contentSection} id="architecture">
            <div className={styles.sectionIntroduction}>
              <p className={appStyles.eyebrow}>Portfolio architecture</p>
              <h2 id="architecture-heading">One system, clear roles</h2>
              <p>API accepts events. Workers deliver them. PostgreSQL keeps the durable record.</p>
            </div>
            <div className={`${appStyles.panel} ${styles.architecturePanel}`}>
              <p><strong>Publisher</strong> sends an event.</p>
              <p><strong>API</strong> validates and saves it.</p>
              <p><strong>Worker</strong> sends the webhook.</p>
              <p><strong>Dashboard</strong> shows what happened.</p>
            </div>
          </section>

          <section aria-labelledby="private-dashboard-heading" className={styles.finalCallToAction}>
            <p className={appStyles.eyebrow}>Private owner workspace</p>
            <h2 id="private-dashboard-heading">See your webhook activity.</h2>
            <p>Sign in with your owner account.</p>
            <Link className={styles.primaryAction} to="/login">Sign in to dashboard</Link>
          </section>
        </main>

        <footer className={styles.footer}>
          <p>RelayForge is a portfolio learning project for observable, at-least-once webhook delivery.</p>
        </footer>
      </PageFrame>
    </>
  )
}
