import styles from './landing.module.css'

const deliveryPath = [
  ['01', 'Publisher', 'Authenticated event and idempotency key'],
  ['02', 'PostgreSQL', 'Durable event and routing intent'],
  ['03', 'Worker', 'Claim, retry, and signed dispatch'],
  ['04', 'Receiver', 'Idempotent handling of delivered requests'],
] as const

export function DeliveryPathVisual() {
  return (
    <figure aria-labelledby="delivery-path-caption" className={styles.deliveryPath}>
      <figcaption id="delivery-path-caption">
        <span>Delivery path</span>
        <strong>One event, observable lifecycle</strong>
      </figcaption>
      <ol className={styles.deliveryPathSteps}>
        {deliveryPath.map(([order, actor, detail]) => (
          <li key={actor}>
            <span aria-hidden="true" className={styles.pathOrder}>{order}</span>
            <div>
              <strong>{actor}</strong>
              <span>{detail}</span>
            </div>
          </li>
        ))}
      </ol>
      <p className={styles.pathFootnote}>Bounded retries and history make the lifecycle inspectable; they do not turn delivery into exactly-once processing.</p>
    </figure>
  )
}
