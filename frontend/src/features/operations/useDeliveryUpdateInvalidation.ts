import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { apiClient } from '../../api/apiClient'

type DeliveryUpdateHint = {
  projectId: string
  deliveryId: string
  observedAt: string
}

const historyQuerySegments = new Set([
  'event-history',
  'event-history-detail',
  'delivery-history',
  'delivery-history-detail',
  'attempt-history',
  'attempt-history-detail',
])

export function useDeliveryUpdateInvalidation(projectId: string) {
  const queryClient = useQueryClient()

  useEffect(() => {
    const invalidateHistory = () => {
      void queryClient.invalidateQueries({
        predicate: (query) => isProjectHistoryQuery(query.queryKey, projectId),
      })
    }
    const source = new EventSource(apiClient.deliveryUpdatesUrl(projectId), { withCredentials: true })

    source.onopen = invalidateHistory
    source.onerror = invalidateHistory
    source.addEventListener('delivery.changed', (event) => {
      const hint = parseDeliveryUpdateHint(event)
      if (hint?.projectId === projectId) {
        invalidateHistory()
      }
    })

    return () => source.close()
  }, [projectId, queryClient])
}

function isProjectHistoryQuery(queryKey: readonly unknown[], projectId: string): boolean {
  return queryKey[0] === 'projects'
    && queryKey[1] === projectId
    && typeof queryKey[2] === 'string'
    && historyQuerySegments.has(queryKey[2])
}

function parseDeliveryUpdateHint(event: Event): DeliveryUpdateHint | null {
  if (!(event instanceof MessageEvent) || typeof event.data !== 'string') {
    return null
  }
  try {
    const data: unknown = JSON.parse(event.data)
    if (!isDeliveryUpdateHint(data)) {
      return null
    }
    return data
  } catch {
    return null
  }
}

function isDeliveryUpdateHint(value: unknown): value is DeliveryUpdateHint {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const data = value as Record<string, unknown>
  return typeof data.projectId === 'string'
    && typeof data.deliveryId === 'string'
    && typeof data.observedAt === 'string'
}
