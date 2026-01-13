// @ts-nocheck
/// <reference types="k6" />
/// <reference types="k6/http" />
/// <reference types="k6/metrics" />

import { check, sleep } from 'k6';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';
import { buildUrl } from '../utils/config';
import { pickProductId } from '../utils/workload';

// K6 글로벌 변수
declare const __ENV: { [key: string]: string };

/**
 * 캐시 스탬피드 시뮬레이션
 *
 * 시나리오:
 * 1) hot key를 충분히 워밍업해서 캐시가 "유효"한 상태에서 시작
 * 2) 특정 시점에 캐시 무효화
 * 3) 무효화 직후 매우 높은 요청률(burst)을 걸어 스탬피드 재현
 */

type StrategyName = 'basic' | 'jitter' | 'jitter-swr' | 'jitter-lock' | 'full' | 'no-cache';

function envString(name: string, fallback: string): string {
  const v = typeof __ENV !== 'undefined' ? __ENV[name] : undefined;
  return v && v.length > 0 ? v : fallback;
}

function envNumber(name: string, fallback: number): number {
  const v = typeof __ENV !== 'undefined' ? __ENV[name] : undefined;
  if (!v) return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function seconds(n: number): string {
  return `${n}s`;
}

const strategy: StrategyName = envString('STRATEGY', 'full') as StrategyName;
const mode = envString('MODE', 'invalidate'); // invalidate | ttl-expiry
const endpointByStrategy: Record<StrategyName, string> = {
  'no-cache': 'no-cache',
  'basic': 'basic',
  'jitter': 'jitter',
  'jitter-swr': 'jitter-swr',
  'jitter-lock': 'jitter-lock',
  'full': 'full',
};

const responseTime = new Trend('stampede_response_time', true);
const errors = new Rate('stampede_errors');
const requests = new Counter('stampede_requests');
const slowPath = new Counter('stampede_slow_path'); // meta.responseTimeMs 기반 "DB 접근 추정"

// TTL-expiry 모드 파라미터 (SWR 테스트용) - 서버 application.yml과 일치시킬 것
const BASE_TTL_SEC = envNumber('BASE_TTL_SEC', 20);       // 서버: cache.stampede.base-ttl-seconds
const SOFT_TTL_RATIO = envNumber('SOFT_TTL_RATIO', 0.5);  // 서버: cache.stampede.soft-ttl-ratio
const SOFT_TTL_SEC = BASE_TTL_SEC * SOFT_TTL_RATIO;       // = 10초
const WAIT_BUFFER_SEC = envNumber('WAIT_BUFFER_SEC', 2);  // stale 구간 확실히 진입하도록 버퍼

// RPS/시간 파라미터
const WARMUP_RPS = envNumber('WARMUP_RPS', 50);
const WARMUP_SEC = envNumber('WARMUP_SEC', 5);            // 짧은 워밍업 (캐시 채우기만)
const INVALIDATE_AT_SEC = envNumber('INVALIDATE_AT_SEC', 15);
const BURST_RPS = envNumber('BURST_RPS', 800);
const BURST_SEC = envNumber('BURST_SEC', 10);
const COOLDOWN_RPS = envNumber('COOLDOWN_RPS', 100);
const COOLDOWN_SEC = envNumber('COOLDOWN_SEC', 20);
const MAX_VUS = envNumber('MAX_VUS', 2000);
const WARMUP_VUS = envNumber('WARMUP_VUS', 50);
const BURST_VUS = envNumber('BURST_VUS', 300);
const COOLDOWN_VUS = envNumber('COOLDOWN_VUS', 100);
const STALE_WAIT_SEC = Math.max(0, SOFT_TTL_SEC - WARMUP_SEC + WAIT_BUFFER_SEC);

const invalidateScenarios = {
  warmup: {
    executor: 'constant-arrival-rate',
    rate: WARMUP_RPS,
    timeUnit: '1s',
    duration: seconds(WARMUP_SEC),
    preAllocatedVUs: WARMUP_VUS,
    maxVUs: MAX_VUS,
    exec: 'hit',
  },
  invalidate: {
    executor: 'per-vu-iterations',
    vus: 1,
    iterations: 1,
    maxDuration: '1s',
    exec: 'invalidate',
    startTime: seconds(INVALIDATE_AT_SEC),
  },
  burst: {
    executor: 'constant-arrival-rate',
    rate: BURST_RPS,
    timeUnit: '1s',
    duration: seconds(BURST_SEC),
    preAllocatedVUs: BURST_VUS,
    maxVUs: MAX_VUS,
    exec: 'hit',
    startTime: seconds(INVALIDATE_AT_SEC),
  },
  cooldown: {
    executor: 'constant-arrival-rate',
    rate: COOLDOWN_RPS,
    timeUnit: '1s',
    duration: seconds(COOLDOWN_SEC),
    preAllocatedVUs: COOLDOWN_VUS,
    maxVUs: MAX_VUS,
    exec: 'hit',
    startTime: seconds(INVALIDATE_AT_SEC + BURST_SEC),
  },
};

const ttlExpiryScenarios = {
  warmup: {
    executor: 'constant-arrival-rate',
    rate: WARMUP_RPS,
    timeUnit: '1s',
    duration: seconds(WARMUP_SEC),
    preAllocatedVUs: WARMUP_VUS,
    maxVUs: MAX_VUS,
    exec: 'hit',
  },
  wait_for_stale: {
    executor: 'shared-iterations',
    vus: 1,
    iterations: 1,
    maxDuration: seconds(STALE_WAIT_SEC + 1),
    exec: 'waitStale',
    startTime: seconds(WARMUP_SEC),
  },
  burst: {
    executor: 'constant-arrival-rate',
    rate: BURST_RPS,
    timeUnit: '1s',
    duration: seconds(BURST_SEC),
    preAllocatedVUs: BURST_VUS,
    maxVUs: MAX_VUS,
    exec: 'hit',
    startTime: seconds(WARMUP_SEC + STALE_WAIT_SEC),
  },
  cooldown: {
    executor: 'constant-arrival-rate',
    rate: COOLDOWN_RPS,
    timeUnit: '1s',
    duration: seconds(COOLDOWN_SEC),
    preAllocatedVUs: COOLDOWN_VUS,
    maxVUs: MAX_VUS,
    exec: 'hit',
    startTime: seconds(WARMUP_SEC + STALE_WAIT_SEC + BURST_SEC),
  },
};

export const options = {
  scenarios: mode === 'ttl-expiry' ? ttlExpiryScenarios : invalidateScenarios,
};

export function setup() {
  console.log('=== Stampede Simulation ===');
  console.log(`strategy=${strategy}`);
  console.log(`mode=${mode}`);
  console.log(`hotKeyId=${envNumber('HOT_KEY_ID', 1)}, hotKeyRatio=${envNumber('HOT_KEY_RATIO', 0.95)}`);
  if (mode === 'ttl-expiry') {
    console.log(`TTL-expiry: softTTL=${SOFT_TTL_SEC}s, wait=${STALE_WAIT_SEC}s`);
  } else {
    console.log(`invalidateAtSec=${INVALIDATE_AT_SEC}`);
  }
  console.log(`burstRps=${BURST_RPS}, burstSec=${BURST_SEC}`);

  return { hotKeyId: envNumber('HOT_KEY_ID', 1) };
}

export function hit(data: { hotKeyId: number }) {
  const productId = pickProductId();
  const url = buildUrl(`/api/v1/products/${productId}/${endpointByStrategy[strategy]}`);

  const res = http.get(url, {
    headers: { 'Content-Type': 'application/json' },
    tags: { strategy },
  });

  requests.add(1);
  responseTime.add(res.timings.duration);

  const ok = check(res, {
    'status is 200': (r: any) => r.status === 200,
    'has strategy meta': (r: any) => {
      try {
        const body = JSON.parse(r.body as string);
        if (strategy === 'full') return body?.meta?.strategy === 'full-protection';
        return body?.meta?.strategy === strategy;
      } catch {
        return false;
      }
    },
  });
  errors.add(!ok);

  // meta.responseTimeMs는 서버 내부에서 측정한 시간 (DB hit면 대략 >= 100ms)
  try {
    const body = JSON.parse(res.body as string);
    if ((body?.meta?.responseTimeMs ?? 0) >= 90) {
      slowPath.add(1);
    }
  } catch {
    // ignore
  }
}

export function invalidate(data: { hotKeyId: number }) {
  const url = buildUrl(`/api/v1/products/${data.hotKeyId}/cache`);
  const res = http.del(url);
  check(res, { 'invalidate status is 200': (r: any) => r.status === 200 });
}

export function waitStale() {
  sleep(STALE_WAIT_SEC);
}

export function handleSummary(data: any) {
  return {
    'stdout': JSON.stringify(data, null, 2),
    'results/stampede-simulation-summary.json': JSON.stringify(data),
  };
}
