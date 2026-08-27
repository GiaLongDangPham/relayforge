import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { type FormEvent, useEffect, useId, useMemo, useState } from 'react'
import { apiClient, ApiProblem, type PublishedEvent, type WebhookEndpointDetails } from '../../api/apiClient'
import styles from './testEvents.module.css'

const samplePayload = '{\n  "invoiceId": "inv_demo_123",\n  "amount": 4200\n}'

type LastRequest = {
  eventType: string
  idempotencyKey: string
  payload: unknown
}

type TestEventsPanelProps = {
  projectId: string
  onViewDeliveries: () => void
}

export function TestEventsPanel({ projectId, onViewDeliveries }: TestEventsPanelProps) {
  const queryClient = useQueryClient()
  const suggestionListId = useId()
  const keyHelpId = useId()
  const payloadErrorId = useId()
  const [apiKey, setApiKey] = useState('')
  const [eventType, setEventType] = useState('')
  const [payloadText, setPayloadText] = useState(samplePayload)
  const [payloadError, setPayloadError] = useState<string | null>(null)
  const [eventTypeError, setEventTypeError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<PublishedEvent | null>(null)
  const [lastRequest, setLastRequest] = useState<LastRequest | null>(null)
  const [publishError, setPublishError] = useState<string | null>(null)

  const endpointsQuery = useInfiniteQuery({
    queryKey: ['projects', projectId, 'endpoints'],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => apiClient.listEndpoints(projectId, pageParam),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  })
  const endpoints = useMemo(
    () => endpointsQuery.data?.pages.flatMap((page) => page.items) ?? [],
    [endpointsQuery.data],
  )
  const suggestedEventTypes = useMemo(
    () => [...new Set(endpoints.flatMap((endpoint) => endpoint.eventTypes))].sort(),
    [endpoints],
  )
  const { fetchNextPage, hasNextPage, isFetchingNextPage } = endpointsQuery

  useEffect(() => {
    if (hasNextPage && !isFetchingNextPage) {
      void fetchNextPage()
    }
  }, [fetchNextPage, hasNextPage, isFetchingNextPage])

  async function publish(command: LastRequest, rememberOnSuccess: boolean) {
    setSubmitting(true)
    setPublishError(null)
    try {
      const published = await apiClient.publishEvent(projectId, apiKey, command.idempotencyKey, command.eventType, command.payload)
      setResult(published)
      if (rememberOnSuccess) {
        setLastRequest(command)
      }
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['projects', projectId, 'event-history'] }),
        queryClient.invalidateQueries({ queryKey: ['projects', projectId, 'delivery-history'] }),
      ])
    } catch (error: unknown) {
      setPublishError(publishErrorMessage(error))
    } finally {
      setSubmitting(false)
    }
  }

  function sendNewEvent(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const normalizedEventType = eventType.trim()
    if (!normalizedEventType) {
      setEventTypeError('Enter an event type before sending.')
      return
    }
    setEventTypeError(null)

    let payload: unknown
    try {
      payload = JSON.parse(payloadText)
      setPayloadError(null)
    } catch {
      setPayloadError('Enter a valid JSON payload before sending.')
      return
    }

    void publish({ eventType: normalizedEventType, idempotencyKey: crypto.randomUUID(), payload }, true)
  }

  function clearKey() {
    setApiKey('')
    setPublishError(null)
  }

  return (
    <section className={styles.panel} aria-labelledby="test-events-heading">
      <div className={styles.heading}>
        <div>
          <h3 id="test-events-heading">Test events</h3>
          <p>This sends an event as a project publisher for local testing.</p>
        </div>
      </div>

      <form className={styles.form} onSubmit={sendNewEvent}>
        <div className={styles.formHeading}>
          <h4>Send a new event</h4>
          <p>Use a project API key; the dashboard never saves it.</p>
        </div>
        <label>
          Project API key
          <input
            aria-describedby={keyHelpId}
            autoComplete="off"
            disabled={submitting}
            name="project-api-key"
            onChange={(event) => setApiKey(event.target.value)}
            type="password"
            value={apiKey}
          />
        </label>
        <p className={styles.muted} id={keyHelpId}>Create an API key in the API keys tab, copy its one-time raw value, then paste it here.</p>
        <button className={styles.secondaryButton} disabled={!apiKey || submitting} onClick={clearKey} type="button">Clear key</button>

        <label>
          Event type
          <input
            aria-describedby={eventTypeError ? `${payloadErrorId}-event-type` : undefined}
            aria-invalid={eventTypeError ? 'true' : undefined}
            disabled={submitting}
            list={suggestionListId}
            onChange={(event) => setEventType(event.target.value)}
            placeholder="invoice.paid"
            required
            value={eventType}
          />
        </label>
        <datalist id={suggestionListId}>
          {suggestedEventTypes.map((type) => <option key={type} value={type} />)}
        </datalist>
        {eventTypeError ? <p className={styles.error} id={`${payloadErrorId}-event-type`} role="alert">{eventTypeError}</p> : null}

        <label>
          JSON payload
          <textarea
            aria-describedby={payloadError ? payloadErrorId : undefined}
            aria-invalid={payloadError ? 'true' : undefined}
            disabled={submitting}
            onChange={(event) => setPayloadText(event.target.value)}
            spellCheck={false}
            value={payloadText}
          />
        </label>
        {payloadError ? <p className={styles.error} id={payloadErrorId} role="alert">{payloadError}</p> : null}

        <div className={styles.actions}>
          <button disabled={!apiKey.trim() || submitting} type="submit">{submitting ? 'Sending event…' : 'Send new event'}</button>
          {lastRequest ? <button className={styles.secondaryButton} disabled={!apiKey.trim() || submitting} onClick={() => void publish(lastRequest, false)} type="button">Repeat last request</button> : null}
        </div>
      </form>

      <EndpointSubscriptions endpoints={endpoints} loading={endpointsQuery.isPending || endpointsQuery.isFetchingNextPage} loadError={endpointsQuery.isError} />

      <details className={styles.guidance}>
        <summary>Local demo guide</summary>
        <ul>
          <li><strong>Success:</strong> an enabled endpoint at <code>http://localhost:8081/webhooks/success</code> should become <strong>SUCCEEDED</strong>.</li>
          <li><strong>No match:</strong> a custom event type without a matching subscription is accepted with <code>deliveryCount: 0</code>.</li>
          <li><strong>Failure or timeout:</strong> configure an endpoint with <code>/webhooks/fail</code> or <code>/webhooks/slow</code> to observe retry and exhaustion.</li>
          <li><strong>Idempotency:</strong> repeat the successful request to receive the same event with <code>idempotentReplay: true</code>. Reusing its key with different content returns a conflict.</li>
        </ul>
      </details>

      {publishError ? <p className={styles.error} role="alert">{publishError}</p> : null}
      {result ? <PublishResult result={result} onViewDeliveries={onViewDeliveries} /> : null}
    </section>
  )
}

function EndpointSubscriptions({ endpoints, loading, loadError }: { endpoints: WebhookEndpointDetails[]; loading: boolean; loadError: boolean }) {
  return (
    <section className={styles.subscriptions} aria-labelledby="endpoint-subscriptions-heading">
      <div>
        <h4 id="endpoint-subscriptions-heading">Routing preview</h4>
        <p className={styles.muted}>Suggestions combine all endpoint subscriptions. Only enabled endpoints create deliveries.</p>
      </div>
      {loading ? <p className={styles.muted}>Loading endpoint subscriptions…</p> : null}
      {loadError ? <p className={styles.error} role="alert">Unable to load endpoint subscriptions. You can still enter a custom event type.</p> : null}
      {!loading && !loadError && endpoints.length === 0 ? <p className={styles.muted}>No endpoint subscriptions are configured. A published custom event can still be accepted with no deliveries.</p> : null}
      <div className={styles.endpointList}>
        {endpoints.map((endpoint) => (
          <article className={styles.endpoint} key={endpoint.id}>
            <div>
              <strong>{endpoint.name}</strong>
              <span className={endpoint.enabled ? styles.enabled : styles.paused}>{endpoint.enabled ? 'Enabled' : 'Paused'}</span>
            </div>
            <code>{endpoint.destinationUrl}</code>
            <p>{endpoint.eventTypes.join(', ')}</p>
          </article>
        ))}
      </div>
    </section>
  )
}

function PublishResult({ result, onViewDeliveries }: { result: PublishedEvent; onViewDeliveries: () => void }) {
  return (
    <section className={styles.result} aria-live="polite" aria-labelledby="publish-result-heading">
      <div>
        <h4 id="publish-result-heading">Event accepted</h4>
        <p>{result.idempotentReplay ? 'RelayForge returned the existing event for this idempotency key.' : 'RelayForge accepted a new publisher event.'}</p>
      </div>
      <dl>
        <div><dt>Event ID</dt><dd><code>{result.eventId}</code></dd></div>
        <div><dt>Accepted</dt><dd>{formatInstant(result.acceptedAt)}</dd></div>
        <div><dt>Deliveries created</dt><dd>{result.deliveryCount}</dd></div>
        <div><dt>Idempotent replay</dt><dd>{result.idempotentReplay ? 'Yes' : 'No'}</dd></div>
      </dl>
      <button className={styles.secondaryButton} onClick={onViewDeliveries} type="button">View in Deliveries</button>
    </section>
  )
}

function publishErrorMessage(error: unknown): string {
  if (error instanceof ApiProblem) {
    if (error.code === 'INVALID_API_KEY' || error.status === 401) {
      return 'The API key was not accepted. Create another key in the API keys tab and paste its raw value here.'
    }
    if (error.code === 'PROJECT_KEY_MISMATCH' || error.status === 403) {
      return 'This API key belongs to a different project. Paste a key created for the selected project.'
    }
    if (error.code === 'IDEMPOTENCY_CONFLICT' || error.status === 409) {
      return 'This idempotency key is already associated with different event content. Send a new event instead.'
    }
    if (error.code === 'PAYLOAD_TOO_LARGE' || error.status === 413) {
      return 'The payload is too large for RelayForge to accept.'
    }
  }
  return 'RelayForge could not accept the event. Check the API key, event type, payload, and local API availability.'
}

function formatInstant(value: string): string {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
