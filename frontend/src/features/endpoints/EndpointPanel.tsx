import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query'
import { type SubmitEvent, useMemo, useState } from 'react'
import { apiClient, ApiProblem, type WebhookEndpointDetails } from '../../api/apiClient'
import { OneTimeSecret } from '../secrets/OneTimeSecret'
import styles from './endpoints.module.css'

const endpointQueryKey = (projectId: string) => ['projects', projectId, 'endpoints'] as const

export function EndpointPanel({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient()
  const [selectedEndpointId, setSelectedEndpointId] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [signingSecret, setSigningSecret] = useState<string | null>(null)
  const endpointsQuery = useInfiniteQuery({
    queryKey: endpointQueryKey(projectId),
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => apiClient.listEndpoints(projectId, pageParam),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  })
  const endpoints = useMemo(() => endpointsQuery.data?.pages.flatMap((page) => page.items) ?? [], [endpointsQuery.data])
  const activeEndpointId = endpoints.some((endpoint) => endpoint.id === selectedEndpointId) ? selectedEndpointId : null
  const activeEndpoint = creating ? null : endpoints.find((endpoint) => endpoint.id === activeEndpointId) ?? null

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: endpointQueryKey(projectId) })
  }

  async function save(command: EndpointCommand) {
    if (activeEndpoint) {
      await apiClient.replaceEndpoint(projectId, activeEndpoint.id, {
        name: command.name,
        destinationUrl: command.destinationUrl,
        eventTypes: command.eventTypes,
        version: activeEndpoint.version,
      })
      await refresh()
      return
    }
    const created = await apiClient.createEndpoint(projectId, { ...command, enabled: command.enabled })
    setSigningSecret(created.signingSecret)
    setSelectedEndpointId(created.id)
    setCreating(false)
    await refresh()
  }

  async function setEnabled(endpoint: WebhookEndpointDetails, enabled: boolean) {
    await apiClient.setEndpointEnabled(projectId, endpoint.id, enabled, endpoint.version)
    await refresh()
  }

  return (
    <section className={styles.panel} aria-labelledby="endpoints-heading">
      <div className={styles.heading}>
        <div>
          <h3 id="endpoints-heading">Endpoints</h3>
          <p>Route subscribed event types to webhook receivers.</p>
        </div>
        <button onClick={() => { setSelectedEndpointId(null); setCreating(true) }} type="button">New endpoint</button>
      </div>
      {signingSecret ? <OneTimeSecret label="endpoint signing secret" onClose={() => setSigningSecret(null)} value={signingSecret} /> : null}
      {endpointsQuery.isPending ? <p className={styles.muted}>Loading endpoints…</p> : null}
      {endpointsQuery.error ? <p className={styles.error} role="alert">Unable to load endpoints.</p> : null}
      <section className={styles.endpointCollection} aria-labelledby="configured-endpoints-heading">
        <div className={styles.collectionHeading}>
          <h4 id="configured-endpoints-heading">Configured endpoints</h4>
          <p className={styles.muted}>Select one to edit its route or delivery state.</p>
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
      </section>
      {endpointsQuery.hasNextPage ? <button disabled={endpointsQuery.isFetchingNextPage} onClick={() => void endpointsQuery.fetchNextPage()} type="button">Load more</button> : null}
      {creating || activeEndpoint ? <EndpointEditor key={activeEndpoint ? `${activeEndpoint.id}:${activeEndpoint.version}` : 'new'} endpoint={activeEndpoint} onSave={save} onToggle={setEnabled} /> : null}
    </section>
  )
}

type EndpointCommand = Pick<WebhookEndpointDetails, 'name' | 'destinationUrl' | 'eventTypes' | 'enabled'>

function EndpointEditor({ endpoint, onSave, onToggle }: {
  endpoint: WebhookEndpointDetails | null
  onSave: (command: EndpointCommand) => Promise<void>
  onToggle: (endpoint: WebhookEndpointDetails, enabled: boolean) => Promise<void>
}) {
  const [name, setName] = useState(endpoint?.name ?? '')
  const [destinationUrl, setDestinationUrl] = useState(endpoint?.destinationUrl ?? '')
  const [eventTypes, setEventTypes] = useState(endpoint?.eventTypes.join(', ') ?? '')
  const [enabled, setEnabled] = useState(endpoint?.enabled ?? true)
  const [submitting, setSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setErrorMessage(null)
    try {
      await onSave({ name, destinationUrl, eventTypes: normalizeEventTypes(eventTypes), enabled })
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
      {endpoint ? <p className={styles.muted}>Editing {endpoint.name}. The signing secret remains unchanged.</p> : null}
      <label>Name<input disabled={submitting} maxLength={100} onChange={(event) => setName(event.target.value)} required value={name} /></label>
      <label>Destination URL<input disabled={submitting} onChange={(event) => setDestinationUrl(event.target.value)} placeholder="https://receiver.example/webhooks" required type="url" value={destinationUrl} /></label>
      <label>Event types (comma-separated)<input disabled={submitting} onChange={(event) => setEventTypes(event.target.value)} placeholder="invoice.paid, invoice.failed" required value={eventTypes} /></label>
      {endpoint ? <p className={styles.muted}>Current state: {endpoint.enabled ? 'enabled' : 'paused'}. Saving configuration does not change state or signing secret.</p> : <label className={styles.checkbox}><input checked={enabled} disabled={submitting} onChange={(event) => setEnabled(event.target.checked)} type="checkbox" /> Enable immediately</label>}
      <div className={styles.actions}>
        <button disabled={submitting} type="submit">{submitting ? 'Saving…' : endpoint ? 'Save configuration' : 'Create endpoint'}</button>
        {endpoint ? <button className={styles.secondaryButton} disabled={submitting} onClick={() => void toggle()} type="button">{endpoint.enabled ? 'Disable' : 'Enable'}</button> : null}
      </div>
      {errorMessage ? <p className={styles.error} role="alert">{errorMessage}</p> : null}
    </form>
  )
}

function normalizeEventTypes(value: string): string[] {
  return [...new Set(value.split(',').map((eventType) => eventType.trim()).filter(Boolean))]
}
