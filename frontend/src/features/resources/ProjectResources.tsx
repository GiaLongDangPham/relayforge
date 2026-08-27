import { useState } from 'react'
import type { ProjectDetails } from '../../api/apiClient'
import { ApiKeyPanel } from '../apiKeys/ApiKeyPanel'
import { EndpointPanel } from '../endpoints/EndpointPanel'
import { DeliveryOperations } from '../operations/DeliveryOperations'
import { TestEventsPanel } from '../testEvents/TestEventsPanel'
import styles from './projectResources.module.css'

type Panel = 'apiKeys' | 'endpoints' | 'testEvents' | 'operations'

export function ProjectResources({ project }: { project: ProjectDetails }) {
  const [panel, setPanel] = useState<Panel>('apiKeys')

  return (
    <section className={styles.resources} aria-label="Project resources">
      <nav className={styles.tabs} aria-label="Project views">
        <Tab active={panel === 'apiKeys'} label="API keys" onClick={() => setPanel('apiKeys')} />
        <Tab active={panel === 'endpoints'} label="Endpoints" onClick={() => setPanel('endpoints')} />
        <Tab active={panel === 'testEvents'} label="Test events" onClick={() => setPanel('testEvents')} />
        <Tab active={panel === 'operations'} label="Deliveries" onClick={() => setPanel('operations')} />
      </nav>
      {panel === 'apiKeys' ? <ApiKeyPanel projectId={project.id} /> : null}
      {panel === 'endpoints' ? <EndpointPanel projectId={project.id} /> : null}
      {panel === 'testEvents' ? <TestEventsPanel key={project.id} onViewDeliveries={() => setPanel('operations')} projectId={project.id} /> : null}
      {panel === 'operations' ? <DeliveryOperations projectId={project.id} /> : null}
    </section>
  )
}

function Tab({ active, label, onClick }: { active: boolean; label: string; onClick: () => void }) {
  return (
    <button aria-current={active ? 'page' : undefined} className={active ? styles.activeTab : styles.tab} onClick={onClick} type="button">
      {label}
    </button>
  )
}
