import type { ReactNode } from 'react'
import { LoadingState, ReadErrorState } from '../ui-state/AsyncState'

type Props = {
  children: ReactNode
  resource: string
  hasData: boolean
  pending: boolean
  failed: boolean
  fetching: boolean
  onRetry: () => void
}

/** Keep stale detail visible, but never turn an unknown read into a domain fact. */
export function HistoryReadState({ children, resource, hasData, pending, failed, fetching, onRetry }: Props) {
  return <>
    {failed ? <ReadErrorState title={`Unable to load ${resource}.`} detail={hasData ? 'Previously loaded details are shown and may be out of date.' : 'These details are unavailable. Try the read again.'} onRetry={onRetry} retrying={fetching} /> : null}
    {!hasData && pending ? <LoadingState label={`Loading ${resource}…`} /> : null}
    {hasData ? children : null}
  </>
}
