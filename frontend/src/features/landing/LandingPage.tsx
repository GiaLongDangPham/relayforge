import { Link } from 'react-router-dom'
import { PageFrame } from '../../app/PageFrame'
import appStyles from '../../app/app.module.css'
import { DeliveryPathVisual } from './DeliveryPathVisual'
import styles from './landing.module.css'

const workflowSteps = [
  ['Publish', 'An authenticated publisher submits an event with an idempotency key.'],
  ['Persist', 'RelayForge records immutable event and routing intent before asynchronous work begins.'],
  ['Dispatch', 'A worker sends a signed webhook request to every matching subscribed endpoint.'],
  ['Inspect', 'Authenticated owners review attempts and can replay an exhausted delivery.'],
] as const

const capabilities = [
  ['Durable acceptance', 'PostgreSQL-backed intent survives an API or worker restart.'],
  ['Signed dispatch', 'Outbound requests carry stable identifiers, a timestamp, and an HMAC signature.'],
  ['Bounded recovery', 'Retryable outcomes use bounded backoff; terminal history stays available for inspection.'],
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
              <h1 id="landing-title">Reliable outbound webhooks, visible from acceptance to final attempt.</h1>
              <p className={styles.heroLead}>
                RelayForge accepts publisher events, persists delivery intent in PostgreSQL, sends signed requests asynchronously with bounded retries, and gives authenticated owners safe history and replay.
              </p>
              <div className={styles.heroActions}>
                <Link className={styles.primaryAction} to="/login">Sign in to dashboard</Link>
                <a className={styles.secondaryAction} href="#workflow">See how delivery works</a>
              </div>
            </div>
            <DeliveryPathVisual />
          </section>

          <ul aria-label="RelayForge delivery foundations" className={styles.proofPoints}>
            <li><strong>Durable intent</strong><span>Acceptance is recorded before asynchronous dispatch begins.</span></li>
            <li><strong>Clear ownership</strong><span>Owners see only their projects, history, and replay actions.</span></li>
            <li><strong>Explicit limits</strong><span>At-least-once, bounded retry, no ordering guarantee.</span></li>
          </ul>

          <section aria-labelledby="workflow-heading" className={styles.contentSection} id="workflow">
            <div className={styles.sectionIntroduction}>
              <p className={appStyles.eyebrow}>One durable workflow</p>
              <h2 id="workflow-heading">Separate acceptance from delivery</h2>
              <p>A receiver outage should not make a publisher request slow, uncertain, or invisible to its owner.</p>
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
              <h2 id="reliability-heading">Designed for failure you can inspect</h2>
              <p>RelayForge is at-least-once delivery: receivers must tolerate duplicates, and delivery ordering is not guaranteed.</p>
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
              <h2 id="architecture-heading">One system, clear responsibilities</h2>
              <p>Java and Spring run separate API and worker processes from one application image. PostgreSQL remains the durable source of truth for delivery work.</p>
            </div>
            <div className={`${appStyles.panel} ${styles.architecturePanel}`}>
              <p><strong>Publisher</strong> authenticates with a project API key.</p>
              <p><strong>API</strong> validates and persists accepted events and delivery intent.</p>
              <p><strong>Worker</strong> claims eligible deliveries and performs signed HTTP dispatch.</p>
              <p><strong>Owner dashboard</strong> safely inspects history and requests manual replay.</p>
            </div>
          </section>

          <section aria-labelledby="private-dashboard-heading" className={styles.finalCallToAction}>
            <p className={appStyles.eyebrow}>Private owner workspace</p>
            <h2 id="private-dashboard-heading">Inspect your own projects and delivery history.</h2>
            <p>Sign in with an owner account configured for this RelayForge environment. No public registration or shared delivery data is available.</p>
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
