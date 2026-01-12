import http from 'k6/http';
import { check, sleep } from 'k6';
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

export const options = {
  scenarios: {
    // 1) 워밍업 (캐시를 채워서 '유효한 캐시' 상태를 만든다)
    warmup: {
      executor: 'constant-arrival-rate',
      rate: envNumber('WARMUP_RPS', 50),
      timeUnit: '1s',
      duration: seconds(envNumber('WARMUP_SEC', 15)),
      preAllocatedVUs: envNumber('WARMUP_VUS', 50),
      maxVUs: envNumber('MAX_VUS', 2000),
      exec: 'hit',
    },

    // 2) 무효화 트리거 (정확한 시점에 1회만 실행)
    invalidate: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      startTime: seconds(envNumber('INVALIDATE_AT_SEC', 15)),
      exec: 'invalidate',
    },

    // 3) 무효화 직후 burst로 스탬피드 유발
    burst: {
      executor: 'constant-arrival-rate',
      rate: envNumber('BURST_RPS', 800),
      timeUnit: '1s',
      duration: seconds(envNumber('BURST_SEC', 10)),
      preAllocatedVUs: envNumber('BURST_VUS', 300),
      maxVUs: envNumber('MAX_VUS', 2000),
      startTime: seconds(envNumber('INVALIDATE_AT_SEC', 15)),
      exec: 'hit',
    },

    // 4) burst 이후 안정화 구간 관찰
    cooldown: {
      executor: 'constant-arrival-rate',
      rate: envNumber('COOLDOWN_RPS', 100),
      timeUnit: '1s',
      duration: seconds(envNumber('COOLDOWN_SEC', 20)),
      preAllocatedVUs: envNumber('COOLDOWN_VUS', 100),
      maxVUs: envNumber('MAX_VUS', 2000),
      startTime: seconds(envNumber('INVALIDATE_AT_SEC', 15) + envNumber('BURST_SEC', 10)),
      exec: 'hit',
    },
  },
};

export function setup() {
  console.log('=== Stampede Simulation ===');
  console.log(`strategy=${strategy}`);
  console.log(`hotKeyId=${envNumber('HOT_KEY_ID', 1)}, hotKeyRatio=${envNumber('HOT_KEY_RATIO', 0.95)}`);
  console.log(`invalidateAtSec=${envNumber('INVALIDATE_AT_SEC', 15)}`);
  console.log(`burstRps=${envNumber('BURST_RPS', 800)}, burstSec=${envNumber('BURST_SEC', 10)}`);

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

export function handleSummary(data: any) {
  return {
    'stdout': JSON.stringify(data, null, 2),
    'results/stampede-simulation-summary.json': JSON.stringify(data),
  };
}
