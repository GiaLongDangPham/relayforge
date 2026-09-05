import { useState, type ReactNode } from 'react'
import appStyles from '../../app/app.module.css'
import { useWalkthroughProgress } from './useWalkthroughProgress'
import styles from './onboarding.module.css'

type FirstDeliveryWalkthroughProps = {
  initiallyOpen?: boolean
  apiKeyCreated: boolean
  deliveryOpened: boolean
  onOpenApiKeys: () => void
  onOpenDeliveries: () => void
  onOpenEndpoints: () => void
  onOpenTestEvents: () => void
  projectId: string
  publishDeliveryCount: number | null
}

type Step = 'endpoint' | 'apiKey' | 'testEvent' | 'delivery'

export function FirstDeliveryWalkthrough({
  initiallyOpen = false,
  apiKeyCreated,
  deliveryOpened,
  onOpenApiKeys,
  onOpenDeliveries,
  onOpenEndpoints,
  onOpenTestEvents,
  projectId,
  publishDeliveryCount,
}: FirstDeliveryWalkthroughProps) {
  const [open, setOpen] = useState(initiallyOpen)
  const { endpointState, activeStep, keyConfirmed, refetch } = useWalkthroughProgress(projectId, apiKeyCreated, publishDeliveryCount, deliveryOpened)

  const steps: Array<{ complete: boolean; detail: ReactNode; key: Step; title: string }> = [
    { key: 'endpoint', title: 'Route an endpoint', complete: endpointState === 'ready', detail: endpointDetail(endpointState, onOpenEndpoints, refetch) },
    {
      key: 'apiKey',
      title: 'Publisher key',
      complete: keyConfirmed,
      detail: apiKeyCreated
        ? 'Key created. Save its one-time value outside RelayForge.'
        : <Action detail="Create a key, then save its one-time value." label="Open API-key setup" onClick={onOpenApiKeys} />,
    },
    {
      key: 'testEvent',
      title: 'Send a test event',
      complete: publishDeliveryCount !== null,
      detail: publishDeliveryCount !== null
        ? 'Event sent. Delivery continues in the background.'
        : <Action detail="Paste your key and send an event." label="Open test-event setup" onClick={onOpenTestEvents} />,
    },
    {
      key: 'delivery',
      title: 'Inspect delivery',
      complete: publishDeliveryCount !== null && publishDeliveryCount > 0 && deliveryOpened,
      detail: deliveryDetail(publishDeliveryCount, deliveryOpened, onOpenDeliveries, onOpenEndpoints),
    },
  ]

  return (
    <section aria-label="Setup guide" className={styles.walkthrough}>
      <button className={appStyles.secondaryButton} type="button" aria-expanded={open} aria-controls="walkthrough-content" onClick={() => setOpen(!open)}>{open ? 'Hide guide' : 'Setup guide'}</button>
      <div id="walkthrough-content" hidden={!open}>
      <div className={styles.walkthroughHeading}>
        <div>
          <h3 id="first-delivery-walkthrough-heading">First webhook</h3>
        </div>
      </div>
      <ol className={styles.steps}>
        {steps.map((step, index) => {
          const current = step.key === activeStep
          return (
            <li aria-current={current ? 'step' : undefined} className={`${styles.step} ${current ? styles.current : ''} ${step.complete ? styles.complete : ''}`} key={step.key}>
              <span aria-hidden="true" className={styles.stepNumber}>{step.complete ? '✓' : index + 1}</span>
              <div className={styles.stepContent}>
                <div className={styles.stepHeading}>
                  <h4>{step.title}</h4>
                  {current ? <span className={styles.currentLabel}>Current step</span> : step.complete ? <span className={styles.completeLabel}>Complete</span> : <span className={styles.pendingLabel}>Up next</span>}
                </div>
              </div>
            </li>
          )
        })}
      </ol>
      <div className={styles.stepDetail}>{activeStep ? steps.find(step => step.key === activeStep)?.detail : <p>Guide complete. Check Deliveries when you need it.</p>}</div>
      </div>
    </section>
  )
}

function endpointDetail(state: 'checking' | 'error' | 'missing' | 'paused' | 'ready', onOpenEndpoints: () => void, refetch: () => Promise<unknown>) {
  if (state === 'checking') return 'Checking your endpoints.'
  if (state === 'error') return <Action detail="Endpoint status is unavailable." label="Retry endpoint check" onClick={() => void refetch()} secondary />
  if (state === 'missing') return <Action detail="Create an enabled endpoint for this event." label="Configure endpoint" onClick={onOpenEndpoints} />
  if (state === 'paused') return <Action detail="Enable an endpoint before sending an event." label="Review endpoints" onClick={onOpenEndpoints} />
  return 'An enabled endpoint is ready.'
}

function deliveryDetail(publishDeliveryCount: number | null, deliveryOpened: boolean, onOpenDeliveries: () => void, onOpenEndpoints: () => void) {
  if (publishDeliveryCount === null) return 'Send a test event first.'
  if (publishDeliveryCount === 0) return <Action detail="No enabled endpoint matched this event." label="Review endpoint routing" onClick={onOpenEndpoints} />
  if (deliveryOpened) return 'Delivery history is open.'
  return <Action detail="Open deliveries to see the result." label="Open deliveries" onClick={onOpenDeliveries} />
}

function Action({ detail, label, onClick, secondary = false }: { detail: string; label: string; onClick: () => void; secondary?: boolean }) {
  return <><p>{detail}</p><button className={secondary ? appStyles.secondaryButton : undefined} onClick={onClick} type="button">{label}</button></>
}
