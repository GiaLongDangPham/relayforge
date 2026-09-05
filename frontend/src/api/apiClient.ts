export type OwnerIdentity = {
  ownerId: string
  loginName: string
}

export type ProjectDetails = {
  id: string
  name: string
  version: number
  createdAt: string
  updatedAt: string
}

export type ProjectPage = {
  items: ProjectDetails[]
  nextCursor: string | null
}

export type ProjectApiKeyDetails = {
  id: string
  displayName: string
  keyHint: string
  createdAt: string
  revokedAt: string | null
}

export type ProjectApiKeyPage = {
  items: ProjectApiKeyDetails[]
  nextCursor: string | null
}

export type CreatedProjectApiKey = ProjectApiKeyDetails & { rawKey: string }

export type WebhookEndpointDetails = {
  id: string
  projectId: string
  name: string
  destinationUrl: string
  eventTypes: string[]
  enabled: boolean
  minimumRetryDelaySeconds: number | null
  version: number
  createdAt: string
  updatedAt: string
}

export type WebhookEndpointPage = {
  items: WebhookEndpointDetails[]
  nextCursor: string | null
}

export type CreatedWebhookEndpoint = WebhookEndpointDetails & { signingSecret: string }

export type EventHistorySummary = {
  id: string
  eventType: string
  acceptedAt: string
  deliveryCount: number
}

export type EventHistoryPage = { items: EventHistorySummary[]; nextCursor: string | null }

export type PublishedEvent = {
  eventId: string
  projectId: string
  eventType: string
  acceptedAt: string
  deliveryCount: number
  idempotentReplay: boolean
}

export type EventHistoryDetails = {
  event: EventHistorySummary
  payload: unknown
  deliverySummary: {
    totalCount: number
    activeCount: number
    succeededCount: number
    failedPermanentCount: number
    exhaustedCount: number
  }
}

export type DeliveryHistorySummary = {
  id: string
  eventId: string
  endpointId: string
  replayOfDeliveryId: string | null
  state: string
  displayStatus: string
  attemptCount: number
  nextAttemptAt: string | null
  createdAt: string
  terminalAt: string | null
}

export type DeliveryHistoryPage = { items: DeliveryHistorySummary[]; nextCursor: string | null }

export type DeliveryProjectHealth = {
  observedAt: string
  dueEnabledCount: number
  oldestDueEnabledAt: string | null
  retryScheduledCount: number
  inFlightCount: number
  pausedCount: number
  exhaustedCount: number
}

export type AttemptHistorySummary = {
  id: string
  attemptNumber: number
  status: string
  startedAt: string
  finishedAt: string | null
  httpStatus: number | null
  failureCode: string | null
  latencyMilliseconds: number | null
}

export type DeliveryHistoryDetails = {
  delivery: DeliveryHistorySummary
  eventType: string
  endpoint: { endpointId: string; name: string; enabled: boolean }
  replayDeliveryIds: string[]
  latestAttempt: AttemptHistorySummary | null
}

export type AttemptHistoryDetails = {
  attempt: AttemptHistorySummary
  destinationFingerprintVersion: number
  destinationFingerprint: string
  responsePreview: string | null
  responseTruncated: boolean
  lateDiagnostic: {
    observedStatus: string
    httpStatus: number | null
    failureCode: string | null
    latencyMilliseconds: number | null
    observedAt: string
  } | null
}

export type ReplayDeliveryResult = {
  sourceDeliveryId: string
  replayDeliveryId: string
  eventId: string
  endpointId: string
  createdAt: string
  idempotentReplay: boolean
}

type CsrfToken = {
  headerName: string
  token: string
}

type ProblemDetails = {
  title?: string
  code?: string
}

export class ApiProblem extends Error {
  readonly status: number
  readonly code?: string

  constructor(status: number, message: string, code?: string) {
    super(message)
    this.name = 'ApiProblem'
    this.status = status
    this.code = code
  }
}

class ApiClient {
  private readonly baseUrl = apiBaseUrl()

  async currentOwner(): Promise<OwnerIdentity> {
    return this.request<OwnerIdentity>('/api/v1/auth/me')
  }

  async login(loginName: string, password: string): Promise<OwnerIdentity> {
    return this.mutate<OwnerIdentity>('/api/v1/auth/session', {
      method: 'POST',
      body: JSON.stringify({ loginName, password }),
    })
  }

  async logout(): Promise<void> {
    await this.mutate('/api/v1/auth/session', { method: 'DELETE' })
  }

  async listProjects(cursor: string | null): Promise<ProjectPage> {
    const parameters = new URLSearchParams({ limit: '20' })
    if (cursor) {
      parameters.set('cursor', cursor)
    }
    return this.request<ProjectPage>(`/api/v1/projects?${parameters.toString()}`)
  }

  async createProject(name: string): Promise<ProjectDetails> {
    return this.mutate<ProjectDetails>('/api/v1/projects', {
      method: 'POST',
      body: JSON.stringify({ name }),
    })
  }

  async renameProject(projectId: string, name: string, version: number): Promise<ProjectDetails> {
    return this.mutate<ProjectDetails>(`/api/v1/projects/${projectId}`, {
      method: 'PATCH',
      body: JSON.stringify({ name, version }),
    })
  }

  async listApiKeys(projectId: string, cursor: string | null): Promise<ProjectApiKeyPage> {
    return this.requestPage<ProjectApiKeyPage>(`/api/v1/projects/${projectId}/api-keys`, cursor)
  }

  async createApiKey(projectId: string, displayName: string): Promise<CreatedProjectApiKey> {
    return this.mutate<CreatedProjectApiKey>(`/api/v1/projects/${projectId}/api-keys`, {
      method: 'POST',
      body: JSON.stringify({ displayName }),
    })
  }

  async revokeApiKey(projectId: string, apiKeyId: string): Promise<ProjectApiKeyDetails> {
    return this.mutate<ProjectApiKeyDetails>(`/api/v1/projects/${projectId}/api-keys/${apiKeyId}/revoke`, {
      method: 'POST',
      body: JSON.stringify({}),
    })
  }

  async listEndpoints(projectId: string, cursor: string | null): Promise<WebhookEndpointPage> {
    return this.requestPage<WebhookEndpointPage>(`/api/v1/projects/${projectId}/endpoints`, cursor)
  }

  async createEndpoint(
    projectId: string,
    command: Pick<WebhookEndpointDetails, 'name' | 'destinationUrl' | 'eventTypes' | 'enabled' | 'minimumRetryDelaySeconds'>,
  ): Promise<CreatedWebhookEndpoint> {
    return this.mutate<CreatedWebhookEndpoint>(`/api/v1/projects/${projectId}/endpoints`, {
      method: 'POST',
      body: JSON.stringify(command),
    })
  }

  async replaceEndpoint(
    projectId: string,
    endpointId: string,
    command: Pick<WebhookEndpointDetails, 'name' | 'destinationUrl' | 'eventTypes' | 'minimumRetryDelaySeconds' | 'version'>,
  ): Promise<WebhookEndpointDetails> {
    return this.mutate<WebhookEndpointDetails>(`/api/v1/projects/${projectId}/endpoints/${endpointId}`, {
      method: 'PUT',
      body: JSON.stringify(command),
    })
  }

  async setEndpointEnabled(
    projectId: string,
    endpointId: string,
    enabled: boolean,
    version: number,
  ): Promise<WebhookEndpointDetails> {
    const operation = enabled ? 'enable' : 'disable'
    return this.mutate<WebhookEndpointDetails>(`/api/v1/projects/${projectId}/endpoints/${endpointId}/${operation}`, {
      method: 'POST',
      body: JSON.stringify({ version }),
    })
  }

  async listEvents(projectId: string, eventType: string): Promise<EventHistoryPage> {
    const parameters = new URLSearchParams({ limit: '20' })
    if (eventType) {
      parameters.set('eventType', eventType)
    }
    return this.request<EventHistoryPage>(`/api/v1/projects/${projectId}/events?${parameters.toString()}`)
  }

  async publishEvent(
    projectId: string,
    rawApiKey: string,
    idempotencyKey: string,
    eventType: string,
    payload: unknown,
  ): Promise<PublishedEvent> {
    return this.publisherRequest<PublishedEvent>(`/api/v1/projects/${projectId}/events`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${rawApiKey}`,
        'Idempotency-Key': idempotencyKey,
      },
      body: JSON.stringify({ eventType, payload }),
    })
  }

  async findEvent(projectId: string, eventId: string): Promise<EventHistoryDetails> {
    return this.request<EventHistoryDetails>(`/api/v1/projects/${projectId}/events/${eventId}`)
  }

  async listDeliveries(projectId: string, eventId: string | null): Promise<DeliveryHistoryPage> {
    const parameters = new URLSearchParams({ limit: '20' })
    if (eventId) {
      parameters.set('eventId', eventId)
    }
    return this.request<DeliveryHistoryPage>(`/api/v1/projects/${projectId}/deliveries?${parameters.toString()}`)
  }

  async findDeliveryHealth(projectId: string): Promise<DeliveryProjectHealth> {
    return this.request<DeliveryProjectHealth>(`/api/v1/projects/${projectId}/delivery-health`)
  }

  async findDelivery(projectId: string, deliveryId: string): Promise<DeliveryHistoryDetails> {
    return this.request<DeliveryHistoryDetails>(`/api/v1/projects/${projectId}/deliveries/${deliveryId}`)
  }

  async listAttempts(projectId: string, deliveryId: string): Promise<AttemptHistorySummary[]> {
    return this.request<AttemptHistorySummary[]>(`/api/v1/projects/${projectId}/deliveries/${deliveryId}/attempts`)
  }

  async findAttempt(projectId: string, deliveryId: string, attemptId: string): Promise<AttemptHistoryDetails> {
    return this.request<AttemptHistoryDetails>(`/api/v1/projects/${projectId}/deliveries/${deliveryId}/attempts/${attemptId}`)
  }

  async replayDelivery(projectId: string, deliveryId: string, idempotencyKey: string): Promise<ReplayDeliveryResult> {
    return this.mutate<ReplayDeliveryResult>(`/api/v1/projects/${projectId}/deliveries/${deliveryId}/replays`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey },
    })
  }

  deliveryUpdatesUrl(projectId: string): string {
    return `${this.baseUrl}/api/v1/projects/${encodeURIComponent(projectId)}/delivery-updates`
  }

  private async mutate<T>(path: string, request: RequestInit): Promise<T> {
    const csrf = await this.request<CsrfToken>('/api/v1/auth/csrf')
    const headers = new Headers(request.headers)
    headers.set(csrf.headerName, csrf.token)
    return this.request<T>(path, { ...request, headers })
  }

  private async requestPage<T>(path: string, cursor: string | null): Promise<T> {
    const parameters = new URLSearchParams({ limit: '20' })
    if (cursor) {
      parameters.set('cursor', cursor)
    }
    return this.request<T>(`${path}?${parameters.toString()}`)
  }

  private async publisherRequest<T>(path: string, request: RequestInit): Promise<T> {
    const headers = new Headers(request.headers)
    headers.set('Accept', 'application/json')
    headers.set('Content-Type', 'application/json')

    const response = await fetch(`${this.baseUrl}${path}`, {
      ...request,
      cache: 'no-store',
      credentials: 'omit',
      headers,
    })

    if (!response.ok) {
      throw await ApiClient.problem(response)
    }
    return response.json() as Promise<T>
  }

  private async request<T>(path: string, request: RequestInit = {}): Promise<T> {
    const headers = new Headers(request.headers)
    headers.set('Accept', 'application/json')
    if (request.body) {
      headers.set('Content-Type', 'application/json')
    }

    const response = await fetch(`${this.baseUrl}${path}`, {
      ...request,
      cache: 'no-store',
      credentials: 'include',
      headers,
    })

    if (!response.ok) {
      throw await ApiClient.problem(response)
    }
    if (response.status === 204) {
      return undefined as T
    }
    return response.json() as Promise<T>
  }

  private static async problem(response: Response): Promise<ApiProblem> {
    const problem = await response.json().catch((): ProblemDetails => ({})) as ProblemDetails
    return new ApiProblem(response.status, problem.title ?? 'Request failed', problem.code)
  }
}

function apiBaseUrl(): string {
  const configuredOrigin = import.meta.env.VITE_API_ORIGIN?.trim()
  return configuredOrigin ? configuredOrigin.replace(/\/$/, '') : 'http://localhost:8080'
}

export const apiClient = new ApiClient()
