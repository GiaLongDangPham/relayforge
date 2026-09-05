import { useQueryClient } from '@tanstack/react-query'
import { type SubmitEvent, useEffect, useMemo, useRef, useState } from 'react'
import { apiClient, ApiProblem, type WebhookEndpointDetails } from '../../api/apiClient'
import { OneTimeSecret } from '../secrets/OneTimeSecret'
import { ActionResult, EmptyState, LoadingState, ReadErrorState } from '../ui-state/AsyncState'
import { InfoTip } from '../ui-state/InfoTip'
import { endpointQueryKey, useEndpointPages } from './useEndpointPages'
import styles from './endpoints.module.css'

export function EndpointPanel({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const [selectedEndpointId, setSelectedEndpointId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const createTrigger = useRef<HTMLButtonElement>(null)
  const [signingSecret, setSigningSecret] = useState<string | null>(null)
  const [actionResult, setActionResult] = useState<string | null>(null)
  const endpointsQuery = useEndpointPages(projectId)
  const endpoints = useMemo(() => endpointsQuery.data?.pages.flatMap((page) => page.items) ?? [], [endpointsQuery.data])
  const activeEndpointId = endpoints.some((endpoint) => endpoint.id === selectedEndpointId) ? selectedEndpointId : null
  const activeEndpoint = creating ? null : endpoints.find((endpoint) => endpoint.id === activeEndpointId) ?? null

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: endpointQueryKey(projectId) })
  }

  async function save(command: EndpointCommand) {
    setActionResult(null)
    if (activeEndpoint) {
      await apiClient.replaceEndpoint(projectId, activeEndpoint.id, {
        name: command.name,
        destinationUrl: command.destinationUrl,
        eventTypes: command.eventTypes,
        minimumRetryDelaySeconds: command.minimumRetryDelaySeconds,
        version: activeEndpoint.version,
      })
      await refresh()
      setActionResult('Endpoint configuration saved.')
      return
    }
    const created = await apiClient.createEndpoint(projectId, { ...command, enabled: command.enabled })
    setSigningSecret(created.signingSecret)
    setSelectedEndpointId(created.id)
    setCreating(false)
    await refresh()
    setActionResult('Endpoint created. Copy its one-time signing secret before closing it.')
  }

  async function setEnabled(endpoint: WebhookEndpointDetails, enabled: boolean) {
    setActionResult(null)
    await apiClient.setEndpointEnabled(projectId, endpoint.id, enabled, endpoint.version)
    await refresh()
    setActionResult(enabled ? 'Endpoint enabled.' : 'Endpoint paused.')
  }

  return (
    <section className={styles.panel} aria-labelledby="endpoints-heading">
      <div className={styles.heading}>
        <div>
          <h3 id="endpoints-heading" tabIndex={-1}>Endpoints</h3>
          <p>Choose where webhook events are sent.</p>
        </div>
        <button ref={createTrigger} onClick={() => { setSelectedEndpointId(null); setCreating(true); setActionResult(null) }} type="button">New endpoint</button>
      </div>
      {signingSecret ? <OneTimeSecret label="endpoint signing secret" onClose={() => setSigningSecret(null)} value={signingSecret} /> : null}
      <ActionResult>{actionResult}</ActionResult>
      {endpointsQuery.isPending ? <LoadingState label="Loading endpoints…" /> : null}
      {endpointsQuery.error ? <ReadErrorState detail={endpoints.length > 0 ? 'The loaded endpoints remain available.' : 'RelayForge cannot determine whether an endpoint is configured.'} onRetry={() => void endpointsQuery.refetch()} retrying={endpointsQuery.isRefetching} title="Unable to load endpoints." /> : null}
      <section className={styles.endpointCollection} aria-labelledby="configured-endpoints-heading">
        <div className={styles.collectionHeading}>
          <h4 id="configured-endpoints-heading">Your endpoints</h4>
        </div>
        <div className={styles.endpointList}>
          {endpoints.map((endpoint) => (
            <button
              aria-pressed={endpoint.id === activeEndpointId && !creating}
              className={endpoint.id === activeEndpointId && !creating ? styles.selectedEndpoint : styles.endpointButton}
              key={endpoint.id}
              onClick={() => { setCreating(false); setSelectedEndpointId(endpoint.id) }}
              type="button"
            >
              <strong>{endpoint.name}</strong>
              <small>{endpoint.enabled ? 'Enabled' : 'Paused'} · {endpoint.eventTypes.join(', ')}</small>
            </button>
          ))}
        </div>
        {!endpointsQuery.isPending && !endpointsQuery.error && endpoints.length === 0 ? <EmptyState detail="Create one to start receiving webhook events." title="No endpoints yet." /> : null}
      </section>
      {endpointsQuery.hasNextPage ? <button disabled={endpointsQuery.isFetchingNextPage} onClick={() => void endpointsQuery.fetchNextPage()} type="button">Load more</button> : null}
      {creating || activeEndpoint ? <EndpointEditor key={activeEndpoint ? `${activeEndpoint.id}:${activeEndpoint.version}` : 'new'} endpoint={activeEndpoint} onSave={save} onToggle={setEnabled} onCancel={() => { setCreating(false); setSelectedEndpointId(null); createTrigger.current?.focus() }} /> : null}
    </section>
  )
}

type EndpointCommand = Pick<WebhookEndpointDetails, 'name' | 'destinationUrl' | 'eventTypes' | 'enabled' | 'minimumRetryDelaySeconds'>

function EndpointEditor({ endpoint, onSave, onToggle, onCancel }: {
  endpoint: WebhookEndpointDetails | null
  onSave: (command: EndpointCommand) => Promise<void>
  onToggle: (endpoint: WebhookEndpointDetails, enabled: boolean) => Promise<void>
  onCancel: () => void
}) {
  const nameInput = useRef<HTMLInputElement>(null)
  useEffect(() => { if (!endpoint) nameInput.current?.focus() }, [endpoint])
  const [name, setName] = useState(endpoint?.name ?? '')
  const [destinationUrl, setDestinationUrl] = useState(endpoint?.destinationUrl ?? '')
  const [eventTypes, setEventTypes] = useState(endpoint?.eventTypes.join(', ') ?? '')
  const [enabled, setEnabled] = useState(endpoint?.enabled ?? true)
  const [minimumRetryDelaySeconds, setMinimumRetryDelaySeconds] = useState(
    endpoint?.minimumRetryDelaySeconds?.toString() ?? '',
  )
  const [submitting, setSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setErrorMessage(null)
    try {
      const retryFloor = minimumRetryDelaySeconds.trim()
      await onSave({
        name,
        destinationUrl,
        eventTypes: normalizeEventTypes(eventTypes),
        enabled,
        minimumRetryDelaySeconds: retryFloor === '' ? null : Number(retryFloor),
      })
    } catch (error: unknown) {
      setErrorMessage(error instanceof ApiProblem && error.status === 409 ? 'This endpoint changed elsewhere. Reload it and try again.' : 'Unable to save this endpoint.')
    } finally {
      setSubmitting(false)
    }
  }

  async function toggle() {
    if (!endpoint) {
      return
    }
    setSubmitting(true)
    setErrorMessage(null)
    try {
      await onToggle(endpoint, !endpoint.enabled)
    } catch {
      setErrorMessage('Unable to change endpoint state.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className={styles.editor} onSubmit={submit}>
      <h4>{endpoint ? 'Endpoint configuration' : 'New endpoint'}</h4>
      {endpoint ? <p className={styles.muted}>The signing secret will not change.</p> : null}
      <label>Name<input ref={nameInput} type="text" name="endpointName" autoComplete="off" disabled={submitting} maxLength={100} onChange={(event) => setName(event.target.value)} required value={name} /></label>
      <div className={styles.field}><span className={styles.fieldLabel}><label htmlFor="endpoint-destination-url">Destination URL</label><InfoTip label="Destination URL">RelayForge sends POST requests to this URL. Use an address you control.</InfoTip></span><input id="endpoint-destination-url" disabled={submitting} onChange={(event) => setDestinationUrl(event.target.value)} placeholder="https://receiver.example/webhooks" required type="url" value={destinationUrl} /></div>
      <div className={styles.field}><span className={styles.fieldLabel}><label htmlFor="endpoint-event-types">Events to send</label><InfoTip label="Events to send">Use comma-separated names such as invoice.paid. This endpoint receives matching events only.</InfoTip></span><input id="endpoint-event-types" disabled={submitting} onChange={(event) => setEventTypes(event.target.value)} placeholder="invoice.paid, invoice.failed" required type="text" value={eventTypes} /></div>
      <details className={styles.advancedSettings}>
        <summary>Advanced delivery settings</summary>
        <div className={styles.field}><span className={styles.fieldLabel}><label htmlFor="endpoint-retry-delay">Minimum retry delay</label><InfoTip label="Minimum retry delay">Adds a minimum wait before retries. It never adds attempts.</InfoTip></span><input id="endpoint-retry-delay" disabled={submitting} max="300" min="5" onChange={(event) => setMinimumRetryDelaySeconds(event.target.value)} placeholder="Use default backoff" step="1" type="number" value={minimumRetryDelaySeconds} /><span className={styles.fieldHint}>Optional · 5–300 seconds.</span></div>
      </details>
      {endpoint ? <p className={styles.muted}>Currently {endpoint.enabled ? 'enabled' : 'paused'}.</p> : <label className={styles.checkbox}><input checked={enabled} disabled={submitting} onChange={(event) => setEnabled(event.target.checked)} type="checkbox" /> Start receiving events now</label>}
      <div className={styles.actions}>
        <button disabled={submitting} type="submit">{submitting ? 'Saving…' : endpoint ? 'Save configuration' : 'Create endpoint'}</button>
        {endpoint ? <button className={styles.secondaryButton} disabled={submitting} onClick={() => void toggle()} type="button">{endpoint.enabled ? 'Disable' : 'Enable'}</button> : null}
        <button className={styles.secondaryButton} disabled={submitting} onClick={onCancel} type="button">Close endpoint form</button>
      </div>
      {errorMessage ? <p className={styles.error} role="alert">{errorMessage}</p> : null}
    </form>
  )
}

function normalizeEventTypes(value: string): string[] {
  return [...new Set(value.split(',').map((eventType) => eventType.trim()).filter(Boolean))]
}
