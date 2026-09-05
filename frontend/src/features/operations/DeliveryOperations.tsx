import { useQuery, useQueryClient } from '@tanstack/react-query'
import { type RefObject, useRef, useState } from 'react'
import {
  apiClient,
  type AttemptHistoryDetails,
  type DeliveryHistorySummary,
  type DeliveryProjectHealth,
  type EventHistorySummary,
  type ReplayDeliveryResult,
} from '../../api/apiClient'
import styles from './deliveryOperations.module.css'
import { useDeliveryUpdateInvalidation } from './useDeliveryUpdateInvalidation'
import { HistoryReadState } from './HistoryReadState'
import { ActionResult, EmptyState as UiEmptyState, LoadingState, ReadErrorState } from '../ui-state/AsyncState'

export function DeliveryOperations({ onOpenEndpoints, projectId }: { onOpenEndpoints: () => void; projectId: string }) {
  const queryClient = useQueryClient()
  useDeliveryUpdateInvalidation(projectId)
  const replayKeys = useRef(new Map<string, string>())
  const eventHistoryRef = useRef<HTMLElement>(null)
  const [eventType, setEventType] = useState('')
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null)
  const [selectedDeliveryId, setSelectedDeliveryId] = useState<string | null>(null)
  const [replayResult, setReplayResult] = useState<ReplayDeliveryResult | null>(null)
  const [replayError, setReplayError] = useState<string | null>(null)

  const healthQuery = useQuery({
    queryKey: ['projects', projectId, 'delivery-health'],
    queryFn: () => apiClient.findDeliveryHealth(projectId),
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  })

  const eventsQuery = useQuery({
    queryKey: ['projects', projectId, 'event-history', { eventType }],
    queryFn: () => apiClient.listEvents(projectId, eventType),
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  })
  const events = eventsQuery.data?.items ?? []
  const activeEventId = events.some((event) => event.id === selectedEventId) ? selectedEventId : events[0]?.id ?? null
  const activeEvent = events.find((event) => event.id === activeEventId) ?? null
  const eventQuery = useQuery({
    queryKey: ['projects', projectId, 'event-history-detail', activeEventId],
    queryFn: () => apiClient.findEvent(projectId, activeEventId!),
    enabled: activeEventId !== null,
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  })
  const deliveriesQuery = useQuery({
    queryKey: ['projects', projectId, 'delivery-history', { eventId: activeEventId }],
    queryFn: () => apiClient.listDeliveries(projectId, activeEventId),
    enabled: activeEventId !== null,
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  })
  const deliveries = deliveriesQuery.data?.items ?? []
  const activeDeliveryId = deliveries.some((delivery) => delivery.id === selectedDeliveryId)
    ? selectedDeliveryId
    : deliveries[0]?.id ?? null
  const activeDelivery = deliveries.find((delivery) => delivery.id === activeDeliveryId) ?? null
  const deliveryQuery = useQuery({
    queryKey: ['projects', projectId, 'delivery-history-detail', activeDeliveryId],
    queryFn: () => apiClient.findDelivery(projectId, activeDeliveryId!),
    enabled: activeDeliveryId !== null,
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  })
  const attemptsQuery = useQuery({
    queryKey: ['projects', projectId, 'attempt-history', activeDeliveryId],
    queryFn: () => apiClient.listAttempts(projectId, activeDeliveryId!),
    enabled: activeDeliveryId !== null,
    refetchInterval: 5_000,
    refetchIntervalInBackground: false,
  })
  const [selectedAttemptId, setSelectedAttemptId] = useState<string | null>(null)
  const attempts = attemptsQuery.data ?? []
  const activeAttemptId = attempts.some((attempt) => attempt.id === selectedAttemptId) ? selectedAttemptId : attempts.at(-1)?.id ?? null
  const attemptQuery = useQuery({
    queryKey: ['projects', projectId, 'attempt-history-detail', activeAttemptId],
    queryFn: () => apiClient.findAttempt(projectId, activeDeliveryId!, activeAttemptId!),
    enabled: activeDeliveryId !== null && activeAttemptId !== null,
  })

  async function replay(delivery: DeliveryHistorySummary) {
    const idempotencyKey = replayKeys.current.get(delivery.id) ?? crypto.randomUUID()
    replayKeys.current.set(delivery.id, idempotencyKey)
    setReplayError(null)
    try {
      const result = await apiClient.replayDelivery(projectId, delivery.id, idempotencyKey)
      setReplayResult(result)
      await queryClient.invalidateQueries({ queryKey: ['projects', projectId, 'delivery-history'] })
      await queryClient.invalidateQueries({ queryKey: ['projects', projectId, 'event-history'] })
    } catch {
      setReplayError('Replay was not accepted. You can retry: this browser keeps the same idempotency key for this delivery.')
    }
  }

  return (
    <section className={styles.panel} aria-labelledby="deliveries-heading">
      <div className={styles.heading}>
        <div>
          <h3 id="deliveries-heading" tabIndex={-1}>Deliveries</h3>
          <p>Track events, deliveries, and attempts.</p>
        </div>
        <button onClick={() => void queryClient.invalidateQueries({ queryKey: ['projects', projectId] })} type="button">Refresh now</button>
      </div>
      <DeliveryHealth
        health={healthQuery.data}
        loading={healthQuery.isPending}
        onBrowseHistory={() => revealEventHistory(eventHistoryRef.current)}
        onOpenEndpoints={onOpenEndpoints}
      />
      {healthQuery.error ? <ReadErrorState detail="Your event history is still available below." onRetry={() => void healthQuery.refetch()} retrying={healthQuery.isRefetching} title="Unable to load delivery health." /> : null}
      <label className={styles.filter}>Filter events<input autoComplete="off" name="eventType" onChange={(event) => { setEventType(event.target.value); setSelectedEventId(null); setSelectedDeliveryId(null) }} placeholder="invoice.paid" type="text" value={eventType} /></label>
      <div className={styles.workspace}>
        <EventList activeEventId={activeEventId} error={eventsQuery.isError} events={events} historyRef={eventHistoryRef} loading={eventsQuery.isPending} onRetry={() => void eventsQuery.refetch()} retrying={eventsQuery.isRefetching} onSelect={(event) => { setSelectedEventId(event.id); setSelectedDeliveryId(null) }} />
        <section className={styles.details}>
          {activeEvent ? <HistoryReadState resource="event details" hasData={eventQuery.data !== undefined} pending={eventQuery.isPending} failed={eventQuery.isError} fetching={eventQuery.isFetching} onRetry={() => void eventQuery.refetch()}><EventDetails event={activeEvent} payload={eventQuery.data?.payload} summary={eventQuery.data?.deliverySummary} /></HistoryReadState> : <EmptyState text="Select an event to inspect its deliveries." />}
          <div className={styles.deliveryWorkspace}>
            <DeliveryList activeDeliveryId={activeDeliveryId} enabled={activeEventId !== null} error={deliveriesQuery.isError} deliveries={deliveries} loading={activeEventId !== null && deliveriesQuery.isPending} onRetry={() => void deliveriesQuery.refetch()} retrying={deliveriesQuery.isRefetching} onSelect={setSelectedDeliveryId} />
            <section className={styles.deliveryInspector} aria-label="Selected delivery details">
              {activeDelivery ? <HistoryReadState resource="delivery details" hasData={deliveryQuery.data !== undefined} pending={deliveryQuery.isPending} failed={deliveryQuery.isError} fetching={deliveryQuery.isFetching} onRetry={() => void deliveryQuery.refetch()}><DeliveryDetails attempt={attemptQuery.data} delivery={activeDelivery} deliveryInfo={deliveryQuery.data} onOpenEndpoints={onOpenEndpoints} onReplay={() => void replay(activeDelivery)} replayError={replayError} replayResult={replayResult} /></HistoryReadState> : <EmptyState text="Select a delivery to inspect its attempts." />}
              <AttemptList activeAttemptId={activeAttemptId} attempts={attempts} enabled={activeDeliveryId !== null} error={attemptsQuery.isError} loading={activeDeliveryId !== null && attemptsQuery.isPending} onRetry={() => void attemptsQuery.refetch()} retrying={attemptsQuery.isRefetching} onSelect={setSelectedAttemptId} />
              {activeAttemptId ? <HistoryReadState resource="attempt diagnostic" hasData={attemptQuery.data !== undefined} pending={attemptQuery.isPending} failed={attemptQuery.isError} fetching={attemptQuery.isFetching} onRetry={() => void attemptQuery.refetch()}><AttemptDiagnostic attempt={attemptQuery.data!} /></HistoryReadState> : null}
            </section>
          </div>
        </section>
      </div>
    </section>
  )
}

function DeliveryHealth({ health, loading, onBrowseHistory, onOpenEndpoints }: {
  health: DeliveryProjectHealth | undefined
  loading: boolean
  onBrowseHistory: () => void
  onOpenEndpoints: () => void
}) {
  return (
    <section aria-busy={loading} className={styles.health} aria-labelledby="delivery-health-heading">
      <div className={styles.healthHeading}>
        <div>
          <h4 id="delivery-health-heading">Delivery health</h4>
        </div>
        <p className={styles.muted}>{health ? `Observed ${formatInstant(health.observedAt)}` : loading ? 'Loading observation…' : 'Observation unavailable'}</p>
      </div>
      {health ? <div className={styles.healthMetrics}>
        <HealthMetric count={health.dueEnabledCount} label="Ready" />
        <HealthMetric count={health.retryScheduledCount} label="Retrying" />
        <HealthMetric count={health.inFlightCount} label="In progress" />
        <HealthMetric count={health.pausedCount} label="Paused" />
        <HealthMetric count={health.exhaustedCount} label="Needs replay" />
      </div> : null}
      {health ? <HealthConclusion health={health} onBrowseHistory={onBrowseHistory} onOpenEndpoints={onOpenEndpoints} /> : null}
    </section>
  )
}

function HealthConclusion({ health, onBrowseHistory, onOpenEndpoints }: {
  health: DeliveryProjectHealth
  onBrowseHistory: () => void
  onOpenEndpoints: () => void
}) {
  const activeCount = health.dueEnabledCount + health.retryScheduledCount + health.inFlightCount

  if (health.pausedCount > 0) {
    return <div className={styles.healthConclusion}>
      <div><strong>{health.pausedCount} {pluralize(health.pausedCount, 'delivery is', 'deliveries are')} paused.</strong><p>Enable the endpoint to continue.</p></div>
      <button onClick={onOpenEndpoints} type="button">Open endpoints</button>
    </div>
  }

  if (health.exhaustedCount > 0) {
    return <div className={styles.healthConclusion}>
      <div><strong>{health.exhaustedCount} {pluralize(health.exhaustedCount, 'delivery needs', 'deliveries need')} replay.</strong><p>Open history, then choose a delivery marked Needs replay.</p></div>
      <button onClick={onBrowseHistory} type="button">View event history</button>
    </div>
  }

  if (activeCount > 0) {
    return <div className={styles.healthConclusion}>
      <div><strong>{activeCount} {pluralize(activeCount, 'delivery is', 'deliveries are')} still processing.</strong><p>Open event history for the current state.</p></div>
      <button onClick={onBrowseHistory} type="button">View event history</button>
    </div>
  }

  return <div className={styles.healthConclusion}>
    <div><strong>No delivery needs attention.</strong><p>Open event history to inspect completed work.</p></div>
    <button onClick={onBrowseHistory} type="button">View event history</button>
  </div>
}

function HealthMetric({ count, label }: { count: number; label: string }) {
  return <div className={styles.healthMetric}><strong>{count}</strong><span>{label}</span></div>
}

function EventList({ activeEventId, error, events, historyRef, loading, onRetry, onSelect, retrying }: { activeEventId: string | null; error: boolean; events: EventHistorySummary[]; historyRef: RefObject<HTMLElement | null>; loading: boolean; onRetry: () => void; onSelect: (event: EventHistorySummary) => void; retrying: boolean }) {
  return <section className={`${styles.list} ${styles.eventList}`} aria-label="Events" ref={historyRef} tabIndex={-1}><div className={styles.listHeading}><h4>Events</h4></div>{loading ? <LoadingState label="Loading event history…" /> : null}{error ? <ReadErrorState detail={events.length > 0 ? 'Loaded events remain available.' : 'No event history is available yet.'} onRetry={onRetry} retrying={retrying} title="Unable to load event history." /> : null}{events.length === 0 && !loading && !error ? <EmptyState text="No accepted events match this filter." /> : null}{events.map((event) => <button aria-pressed={event.id === activeEventId} className={event.id === activeEventId ? styles.selected : styles.listItem} key={event.id} onClick={() => onSelect(event)} type="button"><strong>{event.eventType}</strong><small>{event.deliveryCount} deliveries · {formatInstant(event.acceptedAt)}</small></button>)}</section>
}

function EventDetails({ event, payload, summary }: { event: EventHistorySummary; payload: unknown; summary: { totalCount: number; activeCount: number; succeededCount: number; failedPermanentCount: number; exhaustedCount: number } | undefined }) {
  return <section className={styles.card}><h4>Event</h4><p className={styles.detailTitle}>{event.eventType}</p><p>Accepted {formatInstant(event.acceptedAt)} · {event.deliveryCount} deliveries</p>{summary ? <p className={styles.muted}>Active {summary.activeCount} · Succeeded {summary.succeededCount} · Permanent failures {summary.failedPermanentCount} · Exhausted {summary.exhaustedCount}</p> : null}<details className={styles.technicalDetails}><summary>Event data</summary>{payload === undefined ? <p className={styles.muted}>Loading event data…</p> : <pre>{JSON.stringify(payload, null, 2)}</pre>}</details></section>
}

function DeliveryList({ activeDeliveryId, deliveries, enabled, error, loading, onRetry, onSelect, retrying }: { activeDeliveryId: string | null; deliveries: DeliveryHistorySummary[]; enabled: boolean; error: boolean; loading: boolean; onRetry: () => void; onSelect: (id: string) => void; retrying: boolean }) {
  const replayableCount = deliveries.filter((delivery) => delivery.displayStatus === 'EXHAUSTED').length
  return <section className={styles.list} aria-label="Deliveries"><div className={styles.listHeading}><h4>{enabled ? `${deliveries.length} ${pluralize(deliveries.length, 'delivery', 'deliveries')}` : 'Deliveries'}</h4>{enabled && deliveries.length > 0 ? <p className={styles.muted}>{replayableCount > 0 ? `${replayableCount} ${pluralize(replayableCount, 'delivery is', 'deliveries are')} marked Needs replay.` : 'Select a delivery to view its attempts.'}</p> : null}</div>{!enabled ? <EmptyState text="Select an event to load its deliveries." /> : null}{loading ? <LoadingState label="Loading deliveries…" /> : null}{error ? <ReadErrorState detail={deliveries.length > 0 ? 'Loaded deliveries remain available.' : 'No delivery list is available yet.'} onRetry={onRetry} retrying={retrying} title="Unable to load deliveries." /> : null}{enabled && deliveries.length === 0 && !loading && !error ? <EmptyState text="This event has no deliveries." /> : null}{deliveries.map((delivery) => <button aria-label={delivery.displayStatus === 'EXHAUSTED' ? 'Exhausted delivery, needs replay' : undefined} aria-pressed={delivery.id === activeDeliveryId} className={delivery.id === activeDeliveryId ? styles.selected : styles.listItem} key={delivery.id} onClick={() => onSelect(delivery.id)} type="button"><strong>{deliveryLabel(delivery.displayStatus)}</strong>{delivery.displayStatus === 'EXHAUSTED' ? <span className={styles.needsReplay}>Needs replay</span> : null}<small>{delivery.attemptCount} attempts · {formatInstant(delivery.createdAt)}</small></button>)}</section>
}

function DeliveryDetails({ attempt, delivery, deliveryInfo, onOpenEndpoints, onReplay, replayError, replayResult }: { attempt: AttemptHistoryDetails | undefined; delivery: DeliveryHistorySummary; deliveryInfo: Awaited<ReturnType<typeof apiClient.findDelivery>> | undefined; onOpenEndpoints: () => void; onReplay: () => void; replayError: string | null; replayResult: ReplayDeliveryResult | null }) {
  const replayedThisDelivery = replayResult?.sourceDeliveryId === delivery.id
  const guidance = deliveryStatusGuidance(delivery.displayStatus)
  return <section className={styles.card}><h4>Delivery</h4><p className={styles.detailTitle}>{delivery.displayStatus}</p><p className={styles.statusGuidance}>{guidance}</p>{deliveryInfo ? <p>Endpoint: {deliveryInfo.endpoint.name} · {deliveryInfo.endpoint.enabled ? 'enabled' : 'paused'}</p> : null}<details className={styles.technicalDetails}><summary>Delivery details</summary><p className={styles.muted}>Attempts: {delivery.attemptCount}; next: {formatInstant(delivery.nextAttemptAt)}; terminal: {formatInstant(delivery.terminalAt)}</p></details>{delivery.displayStatus === 'PAUSED' ? <button onClick={onOpenEndpoints} type="button">Open endpoints</button> : null}{delivery.displayStatus === 'EXHAUSTED' ? <button onClick={onReplay} type="button">Replay exhausted delivery</button> : null}<ActionResult>{replayedThisDelivery ? `Replay queued as ${replayResult.replayDeliveryId.slice(0, 8)}…${replayResult.idempotentReplay ? ' (existing replay)' : ''}` : null}</ActionResult>{replayError ? <ReadErrorState onRetry={onReplay} title={replayError} /> : null}{attempt ? <p className={styles.muted}>Open the selected attempt for its response.</p> : null}</section>
}

function AttemptList({ activeAttemptId, attempts, enabled, error, loading, onRetry, onSelect, retrying }: { activeAttemptId: string | null; attempts: Awaited<ReturnType<typeof apiClient.listAttempts>>; enabled: boolean; error: boolean; loading: boolean; onRetry: () => void; onSelect: (id: string) => void; retrying: boolean }) {
  return <section className={styles.list} aria-label="Attempts"><div className={styles.listHeading}><h4>Attempts</h4></div>{!enabled ? <EmptyState text="Select a delivery to load its attempts." /> : null}{loading ? <LoadingState label="Loading attempts…" /> : null}{error ? <ReadErrorState detail={attempts.length > 0 ? 'Loaded attempts remain available.' : 'No attempt list is available yet.'} onRetry={onRetry} retrying={retrying} title="Unable to load attempts." /> : null}{enabled && attempts.length === 0 && !loading && !error ? <EmptyState text="No attempts have started for this delivery." /> : null}{attempts.map((item) => <button aria-pressed={item.id === activeAttemptId} className={item.id === activeAttemptId ? styles.selected : styles.listItem} key={item.id} onClick={() => onSelect(item.id)} type="button"><strong>#{item.attemptNumber} · {item.status}</strong><small>{item.httpStatus ? `HTTP ${item.httpStatus}` : item.failureCode ?? 'in progress'} · {formatInstant(item.startedAt)}</small></button>)}</section>
}

function AttemptDiagnostic({ attempt }: { attempt: AttemptHistoryDetails }) {
  return <section className={styles.preview}><h5>Attempt response</h5>{attempt.responsePreview ? <pre>{attempt.responsePreview}</pre> : <p className={styles.muted}>No response preview was retained.</p>}<details className={styles.technicalDetails}><summary>Technical details</summary><p className={styles.muted}>Destination fingerprint v{attempt.destinationFingerprintVersion}: {attempt.destinationFingerprint}</p>{attempt.responseTruncated ? <p className={styles.muted}>The response preview was truncated.</p> : null}{attempt.lateDiagnostic ? <p className={styles.muted}>Late diagnostic: {attempt.lateDiagnostic.observedStatus} at {formatInstant(attempt.lateDiagnostic.observedAt)}</p> : null}</details></section>
}

function EmptyState({ text }: { text: string }) { return <UiEmptyState title={text} /> }

function deliveryStatusGuidance(status: string): string {
  switch (status) {
    case 'PENDING': return 'Waiting for a worker.'
    case 'CLAIMED': return 'Sending now.'
    case 'RETRY_SCHEDULED': return 'Waiting for its next retry.'
    case 'PAUSED': return 'The endpoint is paused.'
    case 'SUCCEEDED': return 'Delivered successfully.'
    case 'FAILED_PERMANENT': return 'Delivery stopped. Check the attempt details.'
    case 'EXHAUSTED': return 'Automatic retries are finished.'
    default: return 'Check the attempt history for this delivery.'
  }
}

function deliveryLabel(status: string): string {
  switch (status) {
    case 'PENDING': return 'Queued'
    case 'CLAIMED': return 'In progress'
    case 'RETRY_SCHEDULED': return 'Retry scheduled'
    case 'PAUSED': return 'Paused'
    case 'SUCCEEDED': return 'Succeeded'
    case 'FAILED_PERMANENT': return 'Stopped'
    case 'EXHAUSTED': return 'Exhausted'
    default: return status
  }
}

function revealEventHistory(target: HTMLElement | null) {
  if (!target) return
  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  target.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' })
  target.focus({ preventScroll: true })
}

function pluralize(count: number, singular: string, plural: string): string { return count === 1 ? singular : plural }

function formatInstant(value: string | null): string { return value ? new Date(value).toLocaleString() : '—' }
