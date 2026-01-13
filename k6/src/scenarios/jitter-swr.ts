// @ts-nocheck
/// <reference types="k6" />
/// <reference types="k6/http" />
/// <reference types="k6/metrics" />

import { check, sleep } from 'k6';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';
import { buildUrl } from '../utils/config';
import { pickProductId } from '../utils/workload';

// k6 글로벌 변수
declare const __ENV: { [key: string]: string };

/**
 * SWR 전용 TTL 만료 기반 시나리오
 *
 * Phase
 * 1) warmup  : 캐시를 채워 fresh 상태로 만든다.
 * 2) wait    : soft TTL(기본 48s)까지 대기하여 stale 구간 진입.
 * 3) burst   : stale 구간에서 burst를 걸어 "stale 즉시 반환 + 백그라운드 갱신"을 관측.
 * 4) cooldown: 안정화 관찰.
 *
 * 테스트 시간을 줄이려면 ENV로 TTL/비율을 낮추면 됨.
 */

function envNumber(name: string, fallback: number): number {
  const v = typeof __ENV !== 'undefined' ? __ENV[name] : undefined;
  if (!v) return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function seconds(n: number): string {
  return `${n}s`;
}

// 서버 TTL 파라미터 - 서버 application.yml과 일치시킬 것
const BASE_TTL_SEC = envNumber('BASE_TTL_SEC', 20);      // 서버 cache.stampede.base-ttl-seconds
const SOFT_TTL_RATIO = envNumber('SOFT_TTL_RATIO', 0.5); // 서버 cache.stampede.soft-ttl-ratio
const SOFT_TTL_SEC = BASE_TTL_SEC * SOFT_TTL_RATIO;       // = 10초

// 시나리오 파라미터
const WARMUP_SEC = envNumber('WARMUP_SEC', 5);           // 짧은 워밍업 (캐시 채우기만)
const WAIT_BUFFER_SEC = envNumber('WAIT_BUFFER_SEC', 2); // soft TTL 도달 후 여유 (stale 확실히 진입)
const STALE_WAIT_SEC = Math.max(0, SOFT_TTL_SEC - WARMUP_SEC + WAIT_BUFFER_SEC);

const BURST_RPS = envNumber('BURST_RPS', 400);
const BURST_SEC = envNumber('BURST_SEC', 10);
const COOLDOWN_RPS = envNumber('COOLDOWN_RPS', 100);
const COOLDOWN_SEC = envNumber('COOLDOWN_SEC', 10);
const MAX_VUS = envNumber('MAX_VUS', 2000);
const PREALLOCATED_VUS = envNumber('PREALLOCATED_VUS', 200);

// 메트릭
const rt = new Trend('jitter_swr_response_time', true);
const errors = new Rate('jitter_swr_errors');
const requests = new Counter('jitter_swr_requests');
const staleHits = new Counter('jitter_swr_stale_hits');
const cacheHits = new Counter('jitter_swr_cache_hits');
const cacheMisses = new Counter('jitter_swr_cache_misses');

export const options = {
  scenarios: {
    warmup: {
      executor: 'constant-arrival-rate',
      rate: envNumber('WARMUP_RPS', 100),
      timeUnit: '1s',
      duration: seconds(WARMUP_SEC),
      preAllocatedVUs: Math.min(envNumber('WARMUP_RPS', 100) * 2, MAX_VUS),
      maxVUs: MAX_VUS,
      exec: 'hit',
    },
    wait_for_stale: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      startTime: seconds(WARMUP_SEC),
      exec: 'waitStale',
    },
    stale_burst: {
      executor: 'constant-arrival-rate',
      rate: BURST_RPS,
      timeUnit: '1s',
      duration: seconds(BURST_SEC),
      preAllocatedVUs: Math.min(BURST_RPS * 2, MAX_VUS),
      maxVUs: MAX_VUS,
      startTime: seconds(WARMUP_SEC + STALE_WAIT_SEC),
      exec: 'hit',
    },
    cooldown: {
      executor: 'constant-arrival-rate',
      rate: COOLDOWN_RPS,
      timeUnit: '1s',
      duration: seconds(COOLDOWN_SEC),
      preAllocatedVUs: Math.min(COOLDOWN_RPS * 2, MAX_VUS),
      maxVUs: MAX_VUS,
      startTime: seconds(WARMUP_SEC + STALE_WAIT_SEC + BURST_SEC),
      exec: 'hit',
    },
  },
};

export function setup() {
  console.log('=== SWR TTL-expiry scenario ===');
  console.log(`BASE_TTL_SEC=${BASE_TTL_SEC}, SOFT_TTL_RATIO=${SOFT_TTL_RATIO}`);
  console.log(`warmup=${WARMUP_SEC}s, waitForStale=${STALE_WAIT_SEC}s, burst=${BURST_SEC}s @${BURST_RPS} rps`);
}

export function hit() {
  const productId = pickProductId();
  const url = buildUrl(`/api/v1/products/${productId}/jitter-swr`);

  const res = http.get(url, {
    headers: { 'Content-Type': 'application/json' },
    tags: { strategy: 'jitter-swr' },
  });

  requests.add(1);
  rt.add(res.timings.duration);

  const ok = check(res, {
    'status is 200': (r: any) => r.status === 200,
  });
  errors.add(!ok);

  // 응답 시간으로 rough 상태 분류 (서버 메타를 직접 쓰지 않고 클라이언트 기준)
  const d = res.timings.duration;
  if (d < 20) {
    cacheHits.add(1);
  } else if (d < 80) {
    staleHits.add(1);
  } else {
    cacheMisses.add(1);
  }
}

export function waitStale() {
  sleep(STALE_WAIT_SEC);
}

export function handleSummary(data: any) {
  return {
    stdout: JSON.stringify(data, null, 2),
    'results/jitter-swr-summary.json': JSON.stringify(data),
  };
}
