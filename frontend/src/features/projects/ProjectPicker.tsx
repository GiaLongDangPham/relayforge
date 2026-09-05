import { type KeyboardEvent, useRef } from 'react'
import type { ProjectDetails } from '../../api/apiClient'
import styles from './projects.module.css'

type Props = { projects: ProjectDetails[]; selected: ProjectDetails | null; onSelect: (id: string) => void; hasMore: boolean; loadingMore: boolean; onLoadMore: () => void; error: boolean }

export function ProjectPicker({ projects, selected, onSelect, hasMore, loadingMore, onLoadMore, error }: Props) {
  const dialog = useRef<HTMLDialogElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const closeButton = useRef<HTMLButtonElement>(null)

  function openPicker() {
    dialog.current?.showModal()
    requestAnimationFrame(() => closeButton.current?.focus())
  }

  function keepFocusInPicker(event: KeyboardEvent<HTMLDialogElement>) {
    if (event.key !== 'Tab') return
    const focusable = Array.from(dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled)') ?? [])
    if (focusable.length === 0) return
    const first = focusable[0]
    const last = focusable.at(-1)!
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return <>
    <button className={styles.pickerTrigger} ref={trigger} type="button" aria-haspopup="dialog" onClick={openPicker}>
      <span>Project: {selected?.name ?? 'Choose a project'}</span><span aria-hidden="true">▾</span>
    </button>
    <dialog className={styles.pickerDialog} ref={dialog} aria-labelledby="project-picker-title" onClose={() => trigger.current?.focus()} onKeyDown={keepFocusInPicker}>
      <div className={styles.pickerHeading}><h2 id="project-picker-title">Choose a project</h2><button ref={closeButton} type="button" onClick={() => dialog.current?.close()}>Close</button></div>
      <div className={styles.pickerList}>
        {projects.map(project => <button type="button" key={project.id} aria-pressed={project.id === selected?.id} className={project.id === selected?.id ? styles.selectedProject : styles.projectButton} onClick={() => { onSelect(project.id); dialog.current?.close() }}>
          <span>{project.name}</span>{project.id === selected?.id ? <small>Current project</small> : null}
        </button>)}
        {error ? <p role="alert">Unable to load more projects. Try again.</p> : null}
        {hasMore ? <button disabled={loadingMore} type="button" onClick={onLoadMore}>{loadingMore ? 'Loading…' : error ? 'Retry loading projects' : 'Load more projects'}</button> : null}
      </div>
    </dialog>
  </>
}
