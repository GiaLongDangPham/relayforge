import { useCallback } from 'react'
import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query'
import { apiClient, ApiProblem, type OwnerIdentity } from '../../api/apiClient'

type AuthenticationState =
  | { kind: 'checking' }
  | { kind: 'anonymous' }
  | { kind: 'authenticated'; owner: OwnerIdentity }
  | { kind: 'unavailable' }

const sessionQueryKey = ['owner-session'] as const

export function useAuthSession() {
  const queryClient = useQueryClient()
  const currentOwner = useQuery({
    queryKey: sessionQueryKey,
    queryFn: loadCurrentOwner,
  })

  const login = useCallback(async (loginName: string, password: string) => {
    const owner = await apiClient.login(loginName, password)
    clearOwnerData(queryClient)
    queryClient.setQueryData(sessionQueryKey, owner)
  }, [queryClient])

  const logout = useCallback(async () => {
    await apiClient.logout()
    clearOwnerData(queryClient)
    queryClient.setQueryData(sessionQueryKey, null)
  }, [queryClient])

  return { state: authenticationState(currentOwner.data, currentOwner.isPending, currentOwner.isError), login, logout }
}

function clearOwnerData(queryClient: QueryClient) {
  queryClient.removeQueries({
    predicate: (query) => query.queryKey[0] !== sessionQueryKey[0],
  })
}

async function loadCurrentOwner(): Promise<OwnerIdentity | null> {
  try {
    return await apiClient.currentOwner()
  } catch (error: unknown) {
    if (error instanceof ApiProblem && error.status === 401) {
      return null
    }
    throw error
  }
}

function authenticationState(
  owner: OwnerIdentity | null | undefined,
  isPending: boolean,
  isError: boolean,
): AuthenticationState {
  if (isPending) {
    return { kind: 'checking' }
  }
  if (isError) {
    return { kind: 'unavailable' }
  }
  return owner ? { kind: 'authenticated', owner } : { kind: 'anonymous' }
}
