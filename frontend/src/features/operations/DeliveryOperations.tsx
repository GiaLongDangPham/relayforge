import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import {
  apiClient,
  type AttemptHistoryDetails,
  type DeliveryHistorySummary,
  type EventHistorySummary,
  type ReplayDeliveryResult,
} from '../../api/apiClient'
import styles from './deliveryOperations.module.css'

export function DeliveryOperations({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const replayKeys = useRef(new Map<string, string>())
  const [eventType, setEventType] = useState('')
  const [selectedEventId, setSelectedEventId] = useState<string | null>(null)
  const [selectedDeliveryId, setSelectedDeliveryId] = useState<string | null>(null)
  const [replayResult, setReplayResult] = useState<ReplayDeliveryResult | null>(null)
  const [replayError, setReplayError] = useState<string | null>(null)

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
          <h3 id="deliveries-heading">Delivery operations</h3>
          <p>Refreshes every 5 seconds while this tab is visible. Receiver content is shown only as escaped diagnostic text.</p>
        </div>
        <button onClick={() => void queryClient.invalidateQueries({ queryKey: ['projects', projectId] })} type="button">Refresh now</button>
      </div>
      <label className={styles.filter}>Event type filter<input onChange={(event) => { setEventType(event.target.value); setSelectedEventId(null); setSelectedDeliveryId(null) }} placeholder="invoice.paid" value={eventType} /></label>
      {eventsQuery.error ? <p className={styles.error} role="alert">Unable to load event history.</p> : null}
      <div className={styles.columns}>
        <EventList activeEventId={activeEventId} events={events} loading={eventsQuery.isPending} onSelect={(event) => { setSelectedEventId(event.id); setSelectedDeliveryId(null) }} />
        <section className={styles.details}>
          {activeEvent ? <EventDetails event={activeEvent} payload={eventQuery.data?.payload} summary={eventQuery.data?.deliverySummary} /> : <EmptyState text="Select an event to inspect its deliveries." />}
          <DeliveryList activeDeliveryId={activeDeliveryId} deliveries={deliveries} loading={deliveriesQuery.isPending} onSelect={setSelectedDeliveryId} />
          {activeDelivery ? <DeliveryDetails attempt={attemptQuery.data} delivery={activeDelivery} deliveryInfo={deliveryQuery.data} onReplay={() => void replay(activeDelivery)} replayError={replayError} replayResult={replayResult} /> : null}
          <AttemptList activeAttemptId={activeAttemptId} attempt={attemptQuery.data} attempts={attempts} loading={attemptsQuery.isPending} onSelect={setSelectedAttemptId} />
        </section>
      </div>
    </section>
  )
}

function EventList({ activeEventId, events, loading, onSelect }: { activeEventId: string | null; events: EventHistorySummary[]; loading: boolean; onSelect: (event: EventHistorySummary) => void }) {
  return <section className={styles.list} aria-label="Events"><h4>Events</h4>{loading ? <p>Loading…</p> : null}{events.length === 0 && !loading ? <EmptyState text="No accepted events match this filter." /> : null}{events.map((event) => <button aria-pressed={event.id === activeEventId} className={event.id === activeEventId ? styles.selected : styles.listItem} key={event.id} onClick={() => onSelect(event)} type="button"><strong>{event.eventType}</strong><small>{event.deliveryCount} deliveries · {formatInstant(event.acceptedAt)}</small></button>)}</section>
}

function EventDetails({ event, payload, summary }: { event: EventHistorySummary; payload: unknown; summary: { totalCount: number; activeCount: number; succeededCount: number; failedPermanentCount: number; exhaustedCount: number } | undefined }) {
  return <section className={styles.card}><h4>{event.eventType}</h4><p>Accepted {formatInstant(event.acceptedAt)} · {event.deliveryCount} routed deliveries</p>{summary ? <p className={styles.muted}>Active {summary.activeCount} · Succeeded {summary.succeededCount} · Permanent failures {summary.failedPermanentCount} · Exhausted {summary.exhaustedCount}</p> : null}{payload === undefined ? <p className={styles.muted}>Loading payload…</p> : <pre>{JSON.stringify(payload, null, 2)}</pre>}</section>
}

function DeliveryList({ activeDeliveryId, deliveries, loading, onSelect }: { activeDeliveryId: string | null; deliveries: DeliveryHistorySummary[]; loading: boolean; onSelect: (id: string) => void }) {
  return <section className={styles.list} aria-label="Deliveries"><h4>Deliveries for selected event</h4>{loading ? <p>Loading…</p> : null}{deliveries.length === 0 && !loading ? <EmptyState text="This event has no deliveries." /> : null}{deliveries.map((delivery) => <button aria-pressed={delivery.id === activeDeliveryId} className={delivery.id === activeDeliveryId ? styles.selected : styles.listItem} key={delivery.id} onClick={() => onSelect(delivery.id)} type="button"><strong>{delivery.displayStatus}</strong><small>{delivery.attemptCount} attempts · {formatInstant(delivery.createdAt)}</small></button>)}</section>
}

function DeliveryDetails({ attempt, delivery, deliveryInfo, onReplay, replayError, replayResult }: { attempt: AttemptHistoryDetails | undefined; delivery: DeliveryHistorySummary; deliveryInfo: Awaited<ReturnType<typeof apiClient.findDelivery>> | undefined; onReplay: () => void; replayError: string | null; replayResult: ReplayDeliveryResult | null }) {
  const replayedThisDelivery = replayResult?.sourceDeliveryId === delivery.id
  return <section className={styles.card}><h4>Delivery {delivery.displayStatus}</h4><p>Endpoint: {deliveryInfo?.endpoint.name ?? 'Loading…'} · {deliveryInfo?.endpoint.enabled ? 'enabled' : 'paused'}</p><p className={styles.muted}>Attempts: {delivery.attemptCount}; next: {formatInstant(delivery.nextAttemptAt)}; terminal: {formatInstant(delivery.terminalAt)}</p>{delivery.displayStatus === 'EXHAUSTED' ? <button onClick={onReplay} type="button">Replay exhausted delivery</button> : null}{replayedThisDelivery ? <p className={styles.success}>Replay queued as {replayResult.replayDeliveryId.slice(0, 8)}…{replayResult.idempotentReplay ? ' (existing replay)' : ''}</p> : null}{replayError ? <p className={styles.error} role="alert">{replayError}</p> : null}{attempt ? <p className={styles.muted}>Latest response preview is available below.</p> : null}</section>
}

function AttemptList({ activeAttemptId, attempt, attempts, loading, onSelect }: { activeAttemptId: string | null; attempt: AttemptHistoryDetails | undefined; attempts: Awaited<ReturnType<typeof apiClient.listAttempts>>; loading: boolean; onSelect: (id: string) => void }) {
  return <section className={styles.list} aria-label="Attempts"><h4>Attempts (up to 5)</h4>{loading ? <p>Loading…</p> : null}{attempts.length === 0 && !loading ? <EmptyState text="No attempts have started for this delivery." /> : null}{attempts.map((item) => <button aria-pressed={item.id === activeAttemptId} className={item.id === activeAttemptId ? styles.selected : styles.listItem} key={item.id} onClick={() => onSelect(item.id)} type="button"><strong>#{item.attemptNumber} · {item.status}</strong><small>{item.httpStatus ? `HTTP ${item.httpStatus}` : item.failureCode ?? 'in progress'} · {formatInstant(item.startedAt)}</small></button>)}{attempt ? <section className={styles.preview}><h5>Selected attempt diagnostic</h5><p className={styles.muted}>Destination fingerprint v{attempt.destinationFingerprintVersion}: {attempt.destinationFingerprint}</p>{attempt.responsePreview ? <pre>{attempt.responsePreview}</pre> : <p className={styles.muted}>No receiver response preview was retained.</p>}{attempt.responseTruncated ? <p className={styles.muted}>Preview was truncated by the backend.</p> : null}{attempt.lateDiagnostic ? <p className={styles.muted}>Late diagnostic: {attempt.lateDiagnostic.observedStatus} at {formatInstant(attempt.lateDiagnostic.observedAt)}</p> : null}</section> : null}</section>
}

function EmptyState({ text }: { text: string }) { return <p className={styles.muted}>{text}</p> }

function formatInstant(value: string | null): string { return value ? new Date(value).toLocaleString() : '—' }
