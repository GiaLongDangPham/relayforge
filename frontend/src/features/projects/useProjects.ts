import { useInfiniteQuery, useMutation, useQueryClient, type InfiniteData } from '@tanstack/react-query'
import { useMemo } from 'react'
import { apiClient, ApiProblem, type ProjectDetails, type ProjectPage } from '../../api/apiClient'

const projectsQueryKey = ['projects'] as const

export function useProjectPages() {
  const query = useInfiniteQuery({
    queryKey: projectsQueryKey,
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => apiClient.listProjects(pageParam),
    getNextPageParam: (page) => page.nextCursor ?? undefined,
  })
  const projects = useMemo(() => query.data?.pages.flatMap((page) => page.items) ?? [], [query.data])

  return { ...query, projects }
}

export function useCreateProject() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: (name: string) => apiClient.createProject(name),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: projectsQueryKey })
    },
  })
}

export function useRenameProject() {
  const queryClient = useQueryClient()

  return useMutation({
    mutationFn: ({ projectId, name, version }: { projectId: string; name: string; version: number }) =>
      apiClient.renameProject(projectId, name, version),
    onSuccess: (updated) => {
      queryClient.setQueriesData<InfiniteData<ProjectPage>>({ queryKey: projectsQueryKey }, (current) => {
        if (!current) {
          return current
        }
        return {
          ...current,
          pages: current.pages.map((page) => ({
            ...page,
            items: page.items.map((project) => project.id === updated.id ? updated : project),
          })),
        }
      })
    },
    onError: async (error) => {
      if (error instanceof ApiProblem && error.status === 409) {
        await queryClient.invalidateQueries({ queryKey: projectsQueryKey })
      }
    },
  })
}

export type ProjectRenameCommand = Pick<ProjectDetails, 'id' | 'version'> & { name: string }
