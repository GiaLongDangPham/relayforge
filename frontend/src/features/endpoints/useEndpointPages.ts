import { useInfiniteQuery } from '@tanstack/react-query'
import { apiClient } from '../../api/apiClient'

export const endpointQueryKey = (projectId: string) => ['projects', projectId, 'endpoints'] as const

export function useEndpointPages(projectId: string) {
  return useInfiniteQuery({
    queryKey: endpointQueryKey(projectId),
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => apiClient.listEndpoints(projectId, pageParam),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  })
}
