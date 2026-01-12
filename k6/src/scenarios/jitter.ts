import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { config, getRandomProductId, buildUrl } from '../utils/config';

const requestsTotal = new Counter('requests_total');
const cacheHits = new Counter('cache_hits');
const cacheMisses = new Counter('cache_misses');
const errorRate = new Rate('error_rate');
const responseTime = new Trend('response_time', true);

export const options = {
  scenarios: {
    jitter_cache: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: config.rampingOptions.stages,
      gracefulRampDown: '10s',
    },
  },
  thresholds: config.thresholds,
};

export default function () {
  const productId = getRandomProductId();
  const url = buildUrl(`/api/v1/products/${productId}/jitter`);

  const startTime = Date.now();
  const response = http.get(url, {
    headers: { 'Content-Type': 'application/json' },
  });
  const duration = Date.now() - startTime;

  requestsTotal.add(1);
  responseTime.add(duration);

  if (duration < 20) {
    cacheHits.add(1);
  } else {
    cacheMisses.add(1);
  }

  const success = check(response, {
    'status is 200': (r: any) => r.status === 200,
    'response time < 500ms': (r: any) => r.timings.duration < 500,
    'has valid response body': (r: any) => {
      try {
        const body = JSON.parse(r.body as string);
        return body.id !== undefined && body.meta?.strategy === 'jitter';
      } catch {
        return false;
      }
    },
  });

  errorRate.add(!success);

  sleep(0.1);
}

export function handleSummary(data: any) {
  return {
    'stdout': JSON.stringify(data, null, 2),
    'results/jitter-summary.json': JSON.stringify(data),
  };
}
