import { useEffect, useMemo, type ReactNode } from 'react'
import appStyles from '../../app/app.module.css'
import { useEndpointPages } from '../endpoints/useEndpointPages'
import styles from './onboarding.module.css'

type FirstDeliveryWalkthroughProps = {
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
  apiKeyCreated,
  deliveryOpened,
  onOpenApiKeys,
  onOpenDeliveries,
  onOpenEndpoints,
  onOpenTestEvents,
  projectId,
  publishDeliveryCount,
}: FirstDeliveryWalkthroughProps) {
  const {
    data,
    fetchNextPage,
    hasNextPage,
    isError,
    isFetchingNextPage,
    isPending,
    refetch,
  } = useEndpointPages(projectId)
  const endpoints = useMemo(() => data?.pages.flatMap((page) => page.items) ?? [], [data])
  const endpointReady = endpoints.some((endpoint) => endpoint.enabled)

  useEffect(() => {
    if (!isError && hasNextPage && !isFetchingNextPage) {
      void fetchNextPage()
    }
  }, [fetchNextPage, hasNextPage, isError, isFetchingNextPage])

  const endpointState = isError
    ? 'error'
    : isPending || isFetchingNextPage || hasNextPage
      ? 'checking'
      : endpoints.length === 0
        ? 'missing'
        : endpointReady
          ? 'ready'
          : 'paused'

  const activeStep: Step | null = endpointState === 'ready'
    ? !apiKeyCreated
      ? 'apiKey'
      : publishDeliveryCount === null
        ? 'testEvent'
        : publishDeliveryCount === 0 || !deliveryOpened
          ? 'delivery'
          : null
    : 'endpoint'

  const steps: Array<{ complete: boolean; detail: ReactNode; key: Step; title: string }> = [
    { key: 'endpoint', title: 'Route an endpoint', complete: endpointState === 'ready', detail: endpointDetail(endpointState, onOpenEndpoints, refetch) },
    {
      key: 'apiKey',
      title: 'Create an API key',
      complete: apiKeyCreated,
      detail: apiKeyCreated
        ? 'A new raw key was shown once. Keep it outside RelayForge before continuing.'
        : <Action detail="Create a one-time publisher key. RelayForge will not show its raw value in the list later." label="Open API-key setup" onClick={onOpenApiKeys} />,
    },
    {
      key: 'testEvent',
      title: 'Send a test event',
      complete: publishDeliveryCount !== null,
      detail: publishDeliveryCount !== null
        ? 'The event acceptance result is available below; publishing remains asynchronous.'
        : <Action detail="Paste the raw key yourself, choose a subscribed event type, and send one JSON event." label="Open test-event setup" onClick={onOpenTestEvents} />,
    },
    {
      key: 'delivery',
      title: 'Inspect delivery',
      complete: publishDeliveryCount !== null && publishDeliveryCount > 0 && deliveryOpened,
      detail: deliveryDetail(publishDeliveryCount, deliveryOpened, onOpenDeliveries, onOpenEndpoints),
    },
  ]

  return (
    <section aria-labelledby="first-delivery-walkthrough-heading" className={styles.walkthrough}>
      <div className={styles.walkthroughHeading}>
        <div>
          <p className={appStyles.eyebrow}>Guided first delivery</p>
          <h3 id="first-delivery-walkthrough-heading">One clear next action at a time</h3>
        </div>
        <p>Complete the current action to reveal the next one. This guide never stores secrets or changes delivery behavior.</p>
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
                {current || step.complete ? <div className={styles.stepDetail}>{step.detail}</div> : null}
              </div>
            </li>
          )
        })}
      </ol>
    </section>
  )
}

function endpointDetail(state: 'checking' | 'error' | 'missing' | 'paused' | 'ready', onOpenEndpoints: () => void, refetch: () => Promise<unknown>) {
  if (state === 'checking') return 'Checking every configured endpoint before choosing the next action.'
  if (state === 'error') return <Action detail="Endpoint readiness could not be confirmed. Retry before treating a route as missing or ready." label="Retry endpoint check" onClick={() => void refetch()} secondary />
  if (state === 'missing') return <Action detail="An event creates a delivery only for an enabled endpoint with a matching subscription." label="Configure endpoint" onClick={onOpenEndpoints} />
  if (state === 'paused') return <Action detail="Every configured endpoint is paused, so publishing now would create no delivery." label="Review endpoints" onClick={onOpenEndpoints} />
  return 'An enabled subscribed endpoint is ready to receive matching deliveries.'
}

function deliveryDetail(publishDeliveryCount: number | null, deliveryOpened: boolean, onOpenDeliveries: () => void, onOpenEndpoints: () => void) {
  if (publishDeliveryCount === null) return 'Send a test event first. Delivery history is where asynchronous receiver outcomes become visible.'
  if (publishDeliveryCount === 0) return <Action detail="RelayForge accepted the event, but no enabled subscription matched it. This is not a delivery success." label="Review endpoint routing" onClick={onOpenEndpoints} />
  if (deliveryOpened) return 'Delivery history is open. A routed event is still at-least-once and may finish after acceptance.'
  return <Action detail="RelayForge accepted a routed event. Open Delivery history to inspect the asynchronous result." label="Open deliveries" onClick={onOpenDeliveries} />
}

function Action({ detail, label, onClick, secondary = false }: { detail: string; label: string; onClick: () => void; secondary?: boolean }) {
  return <><p>{detail}</p><button className={secondary ? appStyles.secondaryButton : undefined} onClick={onClick} type="button">{label}</button></>
}
