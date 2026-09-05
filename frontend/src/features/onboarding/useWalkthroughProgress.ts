import { useEffect, useMemo } from 'react'
import { useEndpointPages } from '../endpoints/useEndpointPages'

type Step = 'endpoint' | 'apiKey' | 'testEvent' | 'delivery'

export function useWalkthroughProgress(projectId: string, apiKeyCreated: boolean, publishDeliveryCount: number | null, deliveryOpened: boolean) {
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

  const keyConfirmed = apiKeyCreated || publishDeliveryCount !== null
  const activeStep: Step | null = endpointState === 'ready'
    ? !keyConfirmed
      ? 'apiKey'
      : publishDeliveryCount === null
        ? 'testEvent'
        : publishDeliveryCount === 0 || !deliveryOpened
          ? 'delivery'
          : null
    : 'endpoint'

  return { endpointState, activeStep, keyConfirmed, refetch } as const
}
