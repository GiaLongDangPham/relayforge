import { type SubmitEvent, useState } from 'react'
import { ApiProblem, type ProjectDetails } from '../../api/apiClient'
import appStyles from '../../app/app.module.css'
import { ProjectResources } from '../resources/ProjectResources'
import { useCreateProject, useProjectPages, useRenameProject, type ProjectRenameCommand } from './useProjects'
import styles from './projects.module.css'

export function ProjectWorkspace() {
  const { projects, error, fetchNextPage, hasNextPage, isFetchingNextPage, isPending } = useProjectPages()
  const createProject = useCreateProject()
  const renameProject = useRenameProject()
  const [selectedProjectId, setSelectedProjectId] = useState<string | null>(null)
  const activeProjectId = projects.some((project) => project.id === selectedProjectId)
    ? selectedProjectId
    : projects[0]?.id ?? null
  const selectedProject = projects.find((project) => project.id === activeProjectId) ?? null

  async function create(name: string) {
    const created = await createProject.mutateAsync(name)
    setSelectedProjectId(created.id)
  }

  async function rename(command: ProjectRenameCommand) {
    await renameProject.mutateAsync({ projectId: command.id, name: command.name, version: command.version })
  }

  return (
    <main className={styles.workspace}>
      <section className={styles.projectListPanel} aria-labelledby="projects-heading">
        <div className={styles.sectionHeading}>
          <div>
            <p className={appStyles.eyebrow}>Configuration</p>
            <h2 id="projects-heading">Projects</h2>
          </div>
        </div>
        <CreateProjectForm onCreate={create} />
        {isPending ? <p className={styles.muted}>Loading projects…</p> : null}
        {error ? <p className={appStyles.formError} role="alert">Unable to load projects. Refresh the page and try again.</p> : null}
        {!isPending && !error && projects.length === 0 ? <p className={styles.muted}>Create your first project to configure webhook delivery.</p> : null}
        <div className={styles.projectList} aria-label="Owned projects">
          {projects.map((project) => (
            <button
              aria-pressed={project.id === activeProjectId}
              className={project.id === activeProjectId ? styles.selectedProject : styles.projectButton}
              key={project.id}
              onClick={() => setSelectedProjectId(project.id)}
              type="button"
            >
              <span>{project.name}</span>
              <small>Updated {formatInstant(project.updatedAt)}</small>
            </button>
          ))}
        </div>
        {hasNextPage ? (
          <button className={appStyles.secondaryButton} disabled={isFetchingNextPage} onClick={() => void fetchNextPage()} type="button">
            {isFetchingNextPage ? 'Loading…' : 'Load more'}
          </button>
        ) : null}
      </section>
      <section className={`${appStyles.panel} ${styles.projectDetailsPanel}`} aria-live="polite">
        {selectedProject
          ? (
              <>
                <ProjectDetailsPanel key={`${selectedProject.id}:${selectedProject.version}`} project={selectedProject} onRename={rename} />
                <ProjectResources project={selectedProject} />
              </>
            )
          : <EmptyProjectSelection />}
      </section>
    </main>
  )
}

function CreateProjectForm({ onCreate }: { onCreate: (name: string) => Promise<void> }) {
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
      <label>
        New project
        <input
          disabled={submitting}
          maxLength={100}
          onChange={(event) => setName(event.target.value)}
          placeholder="Payments Demo"
          required
          value={name}
        />
      </label>
      <button disabled={submitting} type="submit">{submitting ? 'Creating…' : 'Create project'}</button>
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
        <p className={appStyles.eyebrow}>Project</p>
        <h2>{project.name}</h2>
      </div>
      <span className={styles.version}>Version {project.version}</span>
      <details className={styles.projectSettings}>
        <summary>Project settings</summary>
        <div className={styles.settingsContent}>
          <form className={styles.renameForm} onSubmit={submit}>
            <label>
              Project name
              <input disabled={submitting} maxLength={100} onChange={(event) => setName(event.target.value)} required value={name} />
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
          <p className={styles.muted}>The version is sent on rename. If another dashboard update wins first, RelayForge returns a conflict instead of silently overwriting it.</p>
        </div>
      </details>
    </div>
  )
}

function EmptyProjectSelection() {
  return (
    <div className={styles.emptySelection}>
      <p className={appStyles.eyebrow}>Projects</p>
      <h2>Select or create a project</h2>
      <p className={styles.muted}>Projects define the ownership boundary for API keys, webhook endpoints, and delivery history.</p>
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
