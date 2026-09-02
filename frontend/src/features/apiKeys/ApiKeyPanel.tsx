import { useInfiniteQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { type SubmitEvent, useMemo, useState } from 'react'
import { apiClient, ApiProblem, type ProjectApiKeyDetails } from '../../api/apiClient'
import { OneTimeSecret } from '../secrets/OneTimeSecret'
import styles from './apiKeys.module.css'

const apiKeyQueryKey = (projectId: string) => ['projects', projectId, 'api-keys'] as const

export function ApiKeyPanel({ onRawKeyCreated, projectId }: { onRawKeyCreated?: () => void; projectId: string }) {
  const queryClient = useQueryClient()
  const [rawKey, setRawKey] = useState<string | null>(null)
  const keysQuery = useInfiniteQuery({
    queryKey: apiKeyQueryKey(projectId),
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => apiClient.listApiKeys(projectId, pageParam),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  })
  const keys = useMemo(() => keysQuery.data?.pages.flatMap((page) => page.items) ?? [], [keysQuery.data])
  const revoke = useMutation({
    mutationFn: (apiKeyId: string) => apiClient.revokeApiKey(projectId, apiKeyId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: apiKeyQueryKey(projectId) })
    },
  })

  async function create(displayName: string) {
    // Do not use a mutation here: TanStack's mutation cache must never retain raw key material.
    const created = await apiClient.createApiKey(projectId, displayName)
    setRawKey(created.rawKey)
    onRawKeyCreated?.()
    await queryClient.invalidateQueries({ queryKey: apiKeyQueryKey(projectId) })
  }

  return (
    <section className={styles.panel} aria-labelledby="api-keys-heading">
      <div>
        <h3 id="api-keys-heading" tabIndex={-1}>Publisher API keys</h3>
        <p className={styles.muted}>A publisher uses one key to accept events for this project. Only metadata is listed after creation.</p>
      </div>
      <CreateApiKeyForm onCreate={create} />
      {rawKey ? <OneTimeSecret label="publisher API key" onClose={() => setRawKey(null)} value={rawKey} /> : null}
      {keysQuery.isPending ? <p className={styles.muted}>Loading API keys…</p> : null}
      {keysQuery.error ? <p className={styles.error} role="alert">Unable to load API keys.</p> : null}
      <div className={styles.list}>
        {keys.map((apiKey) => (
          <ApiKeyRow apiKey={apiKey} key={apiKey.id} onRevoke={() => revoke.mutate(apiKey.id)} revoking={revoke.isPending} />
        ))}
      </div>
      {keysQuery.hasNextPage ? (
        <button disabled={keysQuery.isFetchingNextPage} onClick={() => void keysQuery.fetchNextPage()} type="button">
          {keysQuery.isFetchingNextPage ? 'Loading…' : 'Load more'}
        </button>
      ) : null}
    </section>
  )
}

function CreateApiKeyForm({ onCreate }: { onCreate: (displayName: string) => Promise<void> }) {
  const [displayName, setDisplayName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setErrorMessage(null)
    try {
      await onCreate(displayName)
      setDisplayName('')
    } catch (error: unknown) {
      setErrorMessage(error instanceof ApiProblem ? 'Unable to create this API key.' : 'The request failed. Try again.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className={styles.createForm} onSubmit={submit}>
      <label>
        Key display name
        <input disabled={submitting} maxLength={100} onChange={(event) => setDisplayName(event.target.value)} placeholder="Checkout publisher" required value={displayName} />
      </label>
      <button disabled={submitting} type="submit">{submitting ? 'Creating…' : 'Create API key'}</button>
      {errorMessage ? <p className={styles.error} role="alert">{errorMessage}</p> : null}
    </form>
  )
}

function ApiKeyRow({ apiKey, onRevoke, revoking }: { apiKey: ProjectApiKeyDetails; onRevoke: () => void; revoking: boolean }) {
  const active = apiKey.revokedAt === null
  return (
    <article className={styles.row}>
      <div>
        <strong>{apiKey.displayName}</strong>
        <code>{apiKey.keyHint}</code>
        <small>Created {formatInstant(apiKey.createdAt)}</small>
      </div>
      {active ? <button disabled={revoking} onClick={onRevoke} type="button">{revoking ? 'Revoking…' : 'Revoke'}</button> : <span className={styles.revoked}>Revoked</span>}
    </article>
  )
}

function formatInstant(value: string): string {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
