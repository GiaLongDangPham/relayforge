import { type SubmitEvent, useCallback, useEffect, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ApiProblem, type ProjectDetails } from '../../api/apiClient'
import appStyles from '../../app/app.module.css'
import { ProjectPicker } from './ProjectPicker'
import { ProjectResources } from '../resources/ProjectResources'
import { LoadingState, ReadErrorState } from '../ui-state/AsyncState'
import { InfoTip } from '../ui-state/InfoTip'
import { useCreateProject, useProjectPages, useRenameProject, type ProjectRenameCommand } from './useProjects'
import styles from './projects.module.css'

export function ProjectWorkspace() {
  const { projects, error, fetchNextPage, hasNextPage, isFetchingNextPage, isPending, isRefetching, refetch } = useProjectPages()
  const createProject = useCreateProject()
  const renameProject = useRenameProject()
  const [searchParams, setSearchParams] = useSearchParams()
  const [creating, setCreating] = useState(false)
  const createTrigger = useRef<HTMLButtonElement>(null)
  const [newProjectId, setNewProjectId] = useState<string | null>(null)
  const requestedProjectId = searchParams.get('project')
  const requestedProjectExists = requestedProjectId !== null && projects.some((project) => project.id === requestedProjectId)
  const activeProjectId = requestedProjectExists
    ? requestedProjectId
    : requestedProjectId === null ? projects[0]?.id ?? null : null
  const selectedProject = projects.find((project) => project.id === activeProjectId) ?? null
  const isFirstProject = !isPending && !error && projects.length === 0

  const selectProject = useCallback((projectId: string | null, replace = false) => {
    setSearchParams((current) => {
      const next = new URLSearchParams(current)
      if (projectId === null) {
        next.delete('project')
      } else {
        next.set('project', projectId)
      }
      return next
    }, { replace })
  }, [setSearchParams])

  useEffect(() => {
    if (requestedProjectId !== null && !requestedProjectExists && !error && hasNextPage && !isFetchingNextPage) {
      void fetchNextPage()
    }
  }, [error, fetchNextPage, hasNextPage, isFetchingNextPage, requestedProjectExists, requestedProjectId])

  useEffect(() => {
    if (requestedProjectId === null || requestedProjectExists || isPending || error || hasNextPage || isFetchingNextPage) {
      return
    }

    selectProject(projects[0]?.id ?? null, true)
  }, [error, hasNextPage, isFetchingNextPage, isPending, projects, requestedProjectExists, requestedProjectId, selectProject])

  async function create(name: string) {
    // Cache insertion may render before mutateAsync resolves. Keep the old
    // workspace mounted until the new project's initial view is known.
    const created = await createProject.mutateAsync(name)
    setNewProjectId(created.id)
    selectProject(created.id)
    setCreating(false)
  }

  async function rename(command: ProjectRenameCommand) {
    await renameProject.mutateAsync({ projectId: command.id, name: command.name, version: command.version })
  }

  return (
    <main className={styles.workspace} id="workspace" tabIndex={-1}>
      <section className={styles.projectListPanel} aria-label="Project selection">
        {projects.length > 0 ? <>
          <ProjectPicker projects={projects} selected={selectedProject} onSelect={id => { if (id !== activeProjectId) { selectProject(id); setNewProjectId(null); setCreating(false) } }} hasMore={Boolean(hasNextPage)} loadingMore={isFetchingNextPage} onLoadMore={() => void fetchNextPage()} error={Boolean(error)} />
          <button ref={createTrigger} type="button" aria-expanded={creating} onClick={() => setCreating(!creating)}>{creating ? 'Cancel' : '+ New project'}</button>
        </> : null}
        {creating || isFirstProject ? <CreateProjectForm firstProject={isFirstProject} onCreate={create} onCancel={isFirstProject ? undefined : () => { setCreating(false); createTrigger.current?.focus() }} /> : null}
        {isPending ? <LoadingState label="Loading projects…" /> : null}
        {error ? <ReadErrorState detail={projects.length > 0 ? 'Projects already loaded remain available while RelayForge retries the latest read.' : 'No project list is available yet.'} onRetry={() => void refetch()} retrying={isRefetching} title="Unable to load projects." /> : null}
      </section>
      {selectedProject ? <section className={styles.projectDetailsPanel}>
        <ProjectDetailsPanel key={`${selectedProject.id}:${selectedProject.version}`} project={selectedProject} onRename={rename} />
        <ProjectResources key={selectedProject.id} newlyCreated={selectedProject.id === newProjectId} project={selectedProject} />
      </section> : null}
    </main>
  )
}

function CreateProjectForm({ firstProject, onCreate, onCancel }: { firstProject: boolean; onCreate: (name: string) => Promise<void>; onCancel?: () => void }) {
  const nameInput = useRef<HTMLInputElement>(null)
  useEffect(() => { if (!firstProject) nameInput.current?.focus() }, [firstProject])
  const [name, setName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setErrorMessage(null)
    try {
      await onCreate(name)
      setName('')
    } catch (error: unknown) {
      setErrorMessage(projectErrorMessage(error, 'Unable to create this project.'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form className={styles.createForm} onSubmit={submit}>
      {firstProject ? (
        <div className={styles.firstProjectGuide}>
          <p className={appStyles.eyebrow}>First setup</p>
          <h3>Create your first project</h3>
          <p>Start by grouping one app's endpoints and event history.</p>
        </div>
      ) : null}
      <label>
        New project
        <input
          type="text" name="projectName" autoComplete="off"
          ref={nameInput}
          disabled={submitting}
          maxLength={100}
          onChange={(event) => setName(event.target.value)}
          placeholder="Payments Demo"
          required
          value={name}
        />
      </label>
      <button disabled={submitting} type="submit">{submitting ? 'Creating…' : 'Create project'}</button>
      {onCancel ? <button disabled={submitting} type="button" onClick={onCancel}>Cancel project creation</button> : null}
      {errorMessage ? <p className={appStyles.formError} role="alert">{errorMessage}</p> : null}
    </form>
  )
}

function ProjectDetailsPanel({ project, onRename }: { project: ProjectDetails; onRename: (command: ProjectRenameCommand) => Promise<void> }) {
  const [name, setName] = useState(project.name)
  const [submitting, setSubmitting] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault()
    setSubmitting(true)
    setErrorMessage(null)
    try {
      await onRename({ id: project.id, name, version: project.version })
    } catch (error: unknown) {
      setErrorMessage(projectErrorMessage(error, 'Unable to rename this project.'))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className={styles.projectContext}>
      <div className={styles.projectIdentity}>
        <h2>{project.name}</h2>
      </div>
      <details className={styles.projectSettings}>
        <summary>Project settings</summary>
        <div className={styles.settingsContent}>
          <span className={styles.version}>Version {project.version}</span>
          <form className={styles.renameForm} onSubmit={submit}>
            <label>
              Project name
              <input type="text" name="renameProject" autoComplete="off" disabled={submitting} maxLength={100} onChange={(event) => setName(event.target.value)} required value={name} />
            </label>
            <button disabled={submitting || name === project.name} type="submit">
              {submitting ? 'Saving…' : 'Save name'}
            </button>
          </form>
          {errorMessage ? <p className={appStyles.formError} role="alert">{errorMessage}</p> : null}
          <dl className={styles.metadata}>
            <div><dt>Created</dt><dd>{formatInstant(project.createdAt)}</dd></div>
            <div><dt>Updated</dt><dd>{formatInstant(project.updatedAt)}</dd></div>
          </dl>
          <p className={styles.muted}>Changes are protected from accidental overwrites. <InfoTip label="Project changes">If someone saves first, RelayForge asks you to review the latest project before saving again.</InfoTip></p>
        </div>
      </details>
    </div>
  )
}

function projectErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof ApiProblem && error.status === 409) {
    return 'This project changed elsewhere. The latest version was reloaded; review it and try again.'
  }
  return fallback
}

function formatInstant(value: string): string {
  return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}
