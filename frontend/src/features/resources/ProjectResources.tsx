import { useEffect, useRef, useState } from 'react'
import type { ProjectDetails } from '../../api/apiClient'
import { ApiKeyPanel } from '../apiKeys/ApiKeyPanel'
import { EndpointPanel } from '../endpoints/EndpointPanel'
import { FirstDeliveryWalkthrough } from '../onboarding/FirstDeliveryWalkthrough'
import { DeliveryOperations } from '../operations/DeliveryOperations'
import { TestEventsPanel } from '../testEvents/TestEventsPanel'
import styles from './projectResources.module.css'

type Panel = 'apiKeys' | 'endpoints' | 'testEvents' | 'operations'

const panelHeadingId: Record<Panel, string> = {
  apiKeys: 'api-keys-heading',
  endpoints: 'endpoints-heading',
  testEvents: 'test-events-heading',
  operations: 'deliveries-heading',
}

export function ProjectResources({ project }: { project: ProjectDetails }) {
  const [panel, setPanel] = useState<Panel>('apiKeys')
  const [apiKeyCreated, setApiKeyCreated] = useState(false)
  const [publishDeliveryCount, setPublishDeliveryCount] = useState<number | null>(null)
  const [deliveryOpened, setDeliveryOpened] = useState(false)
  const panelToReveal = useRef<Panel | null>(null)

  useEffect(() => {
    const targetPanel = panelToReveal.current
    if (!targetPanel || panel !== targetPanel) return

    revealPanelHeading(targetPanel)
    panelToReveal.current = null
  }, [panel])

  function openPanel(nextPanel: Panel) {
    if (panel === nextPanel) {
      revealPanelHeading(nextPanel)
      return
    }

    setPanel(nextPanel)
    panelToReveal.current = nextPanel
  }

  function openDeliveries() {
    openPanel('operations')
    setDeliveryOpened(true)
  }

  function recordPublishedEvent(deliveryCount: number) {
    setPublishDeliveryCount(deliveryCount)
    setDeliveryOpened(false)
  }

  return (
    <section className={styles.resources} aria-label="Project resources">
      <FirstDeliveryWalkthrough
        apiKeyCreated={apiKeyCreated}
        deliveryOpened={deliveryOpened}
        onOpenApiKeys={() => openPanel('apiKeys')}
        onOpenDeliveries={openDeliveries}
        onOpenEndpoints={() => openPanel('endpoints')}
        onOpenTestEvents={() => openPanel('testEvents')}
        projectId={project.id}
        publishDeliveryCount={publishDeliveryCount}
      />
      <nav className={styles.tabs} aria-label="Project views">
        <Tab active={panel === 'apiKeys'} label="API keys" onClick={() => setPanel('apiKeys')} />
        <Tab active={panel === 'endpoints'} label="Endpoints" onClick={() => setPanel('endpoints')} />
        <Tab active={panel === 'testEvents'} label="Test events" onClick={() => setPanel('testEvents')} />
        <Tab active={panel === 'operations'} label="Deliveries" onClick={() => setPanel('operations')} />
      </nav>
      {panel === 'apiKeys' ? <ApiKeyPanel onRawKeyCreated={() => setApiKeyCreated(true)} projectId={project.id} /> : null}
      {panel === 'endpoints' ? <EndpointPanel projectId={project.id} /> : null}
      {panel === 'testEvents' ? <TestEventsPanel key={project.id} onPublishAccepted={(result) => recordPublishedEvent(result.deliveryCount)} onViewDeliveries={openDeliveries} projectId={project.id} /> : null}
      {panel === 'operations' ? <DeliveryOperations projectId={project.id} /> : null}
    </section>
  )
}

function revealPanelHeading(panel: Panel) {
  const heading = document.getElementById(panelHeadingId[panel])
  if (!heading) return

  const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  heading.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' })
  heading.focus({ preventScroll: true })
}

function Tab({ active, label, onClick }: { active: boolean; label: string; onClick: () => void }) {
  return (
    <button aria-current={active ? 'page' : undefined} className={active ? styles.activeTab : styles.tab} onClick={onClick} type="button">
      {label}
    </button>
  )
}
