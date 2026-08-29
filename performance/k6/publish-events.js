import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const acceptedEvents = new Counter('relayforge_publish_accepted_events');
const publishAcceptance = new Trend('relayforge_publish_acceptance_seconds', true);

const baseUrl = __ENV.K6_BASE_URL || 'http://api:8080';
const projectId = required('K6_PROJECT_ID');
const publisherApiKey = required('K6_PUBLISHER_API_KEY');
const eventType = __ENV.K6_EVENT_TYPE || 'performance.accepted';
const runId = __ENV.K6_RUN_ID || `group17-${Date.now()}`;

export const options = {
  scenarios: {
    publisher: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '15s', target: 2 },
        { duration: '30s', target: 5 },
        { duration: '15s', target: 0 }
      ],
      gracefulRampDown: '5s'
    }
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'],
    relayforge_publish_acceptance_seconds: ['p(95)<1000']
  }
};

export default function () {
  const idempotencyKey = `${runId}-vu-${__VU}-iteration-${__ITER}`;
  const payload = JSON.stringify({
    eventType,
    payload: {
      benchmarkRunId: runId,
      virtualUser: __VU,
      iteration: __ITER
    }
  });
  const response = http.post(`${baseUrl}/api/v1/projects/${projectId}/events`, payload, {
    headers: {
      Authorization: `Bearer ${publisherApiKey}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey
    },
    tags: {
      operation: 'publish_event'
    }
  });

  publishAcceptance.add(response.timings.duration / 1000);
  const accepted = check(response, {
    'publish accepted with HTTP 202': (result) => result.status === 202,
    'publish response contains an event id': (result) => {
      try {
        return Boolean(result.json('eventId'));
      } catch (_) {
        return false;
      }
    }
  });
  if (accepted) {
    acceptedEvents.add(1);
  }

  sleep(0.1);
}

function required(name) {
  const value = __ENV[name];
  if (!value) {
    throw new Error(`${name} must be set. Run scripts/setup-group17-loadtest-fixture.ps1 first.`);
  }
  return value;
}
