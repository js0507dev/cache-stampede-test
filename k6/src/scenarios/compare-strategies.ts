import { check, sleep } from 'k6';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';
import { buildUrl } from '../utils/config';
import { pickProductId } from '../utils/workload';

// K6 글로벌 변수
declare const __ENV: { [key: string]: string };

/**
 * TTL 만료 기반 캐시 스탬피드 비교 테스트
 * 
 * 각 전략별로:
 * 1. warmup: 캐시를 채움
 * 2. wait: TTL 만료 시점까지 대기
 * 3. burst: 만료 직후 폭주 발생 → stampede 방지 효과 확인
 * 4. cooldown: 안정화
 */

type StrategyName = 'basic' | 'jitter' | 'jitter-swr' | 'jitter-lock' | 'full';

interface StrategyConfig {
  name: StrategyName;
  endpoint: string;
  waitSeconds: number; // warmup 이후 대기 시간
  description: string;
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

// 서버 TTL 설정 (application.yml과 일치)
const BASE_TTL_SEC = envNumber('BASE_TTL_SEC', 20);
const JITTER_MAX_SEC = envNumber('JITTER_MAX_SEC', 5);
const SOFT_TTL_RATIO = envNumber('SOFT_TTL_RATIO', 0.5);
const SOFT_TTL_SEC = BASE_TTL_SEC * SOFT_TTL_RATIO;

// 시나리오 파라미터
const WARMUP_RPS = envNumber('WARMUP_RPS', 50);
const WARMUP_SEC = envNumber('WARMUP_SEC', 5);
const BURST_RPS = envNumber('BURST_RPS', 1000);
const BURST_SEC = envNumber('BURST_SEC', 20); // 10 → 20초로 증가
const COOLDOWN_RPS = envNumber('COOLDOWN_RPS', 100);
const COOLDOWN_SEC = envNumber('COOLDOWN_SEC', 10);
const MAX_VUS = envNumber('MAX_VUS', 2000);
const PREALLOCATED_VUS = envNumber('PREALLOCATED_VUS', 200);
const GAP_SEC = envNumber('GAP_SEC', 5);

// 전략별 설정
const strategies: StrategyConfig[] = [
  {
    name: 'basic',
    endpoint: 'basic',
    waitSeconds: Math.max(0, BASE_TTL_SEC - WARMUP_SEC + 2), // 20 - 5 + 2 = 17s
    description: 'No stampede protection (hard TTL)',
  },
  {
    name: 'jitter',
    endpoint: 'jitter',
    waitSeconds: Math.max(0, BASE_TTL_SEC + JITTER_MAX_SEC - WARMUP_SEC + 2), // 20 + 5 - 5 + 2 = 22s
    description: 'TTL jitter (hard TTL + random jitter)',
  },
  {
    name: 'jitter-swr',
    endpoint: 'jitter-swr',
    waitSeconds: Math.max(0, SOFT_TTL_SEC - WARMUP_SEC + 2), // 10 - 5 + 2 = 7s
    description: 'SWR (soft TTL expiry)',
  },
  {
    name: 'jitter-lock',
    endpoint: 'jitter-lock',
    waitSeconds: Math.max(0, BASE_TTL_SEC + JITTER_MAX_SEC - WARMUP_SEC + 2), // 22s
    description: 'Distributed lock (hard TTL)',
  },
];

// 메트릭 (전체 - warmup/burst/cooldown 합산)
const durationMsByStrategy: Record<StrategyName, Trend> = {
  'basic': new Trend('basic_response_time', true),
  'jitter': new Trend('jitter_response_time', true),
  'jitter-swr': new Trend('jitter_swr_response_time', true),
  'jitter-lock': new Trend('jitter_lock_response_time', true),
  'full': new Trend('full_response_time', true),
};

const errorsByStrategy: Record<StrategyName, Rate> = {
  'basic': new Rate('basic_errors'),
  'jitter': new Rate('jitter_errors'),
  'jitter-swr': new Rate('jitter_swr_errors'),
  'jitter-lock': new Rate('jitter_lock_errors'),
  'full': new Rate('full_errors'),
};

const requestsByStrategy: Record<StrategyName, Counter> = {
  'basic': new Counter('basic_requests'),
  'jitter': new Counter('jitter_requests'),
  'jitter-swr': new Counter('jitter_swr_requests'),
  'jitter-lock': new Counter('jitter_lock_requests'),
  'full': new Counter('full_requests'),
};

const slowPathByStrategy: Record<StrategyName, Counter> = {
  'basic': new Counter('basic_slow_path'),
  'jitter': new Counter('jitter_slow_path'),
  'jitter-swr': new Counter('jitter_swr_slow_path'),
  'jitter-lock': new Counter('jitter_lock_slow_path'),
  'full': new Counter('full_slow_path'),
};

// ===== BURST 전용 메트릭 (TTL 만료 stampede 시점만 측정) =====
const burstResponseTime: Record<StrategyName, Trend> = {
  'basic': new Trend('basic_burst_response_time', true),
  'jitter': new Trend('jitter_burst_response_time', true),
  'jitter-swr': new Trend('jitter_swr_burst_response_time', true),
  'jitter-lock': new Trend('jitter_lock_burst_response_time', true),
  'full': new Trend('full_burst_response_time', true),
};

const burstSlowPath: Record<StrategyName, Counter> = {
  'basic': new Counter('basic_burst_slow_path'),
  'jitter': new Counter('jitter_burst_slow_path'),
  'jitter-swr': new Counter('jitter_swr_burst_slow_path'),
  'jitter-lock': new Counter('jitter_lock_burst_slow_path'),
  'full': new Counter('full_burst_slow_path'),
};

const burstRequests: Record<StrategyName, Counter> = {
  'basic': new Counter('basic_burst_requests'),
  'jitter': new Counter('jitter_burst_requests'),
  'jitter-swr': new Counter('jitter_swr_burst_requests'),
  'jitter-lock': new Counter('jitter_lock_burst_requests'),
  'full': new Counter('full_burst_requests'),
};

// Full 전략용 soft/hard 분리 메트릭
const fullSoftBurstResponseTime = new Trend('full_soft_burst_response_time', true);
const fullSoftBurstSlowPath = new Counter('full_soft_burst_slow_path');
const fullSoftBurstRequests = new Counter('full_soft_burst_requests');

const fullHardBurstResponseTime = new Trend('full_hard_burst_response_time', true);
const fullHardBurstSlowPath = new Counter('full_hard_burst_slow_path');
const fullHardBurstRequests = new Counter('full_hard_burst_requests');

// 시나리오 동적 생성
export const options = {
  scenarios: {} as any,
};

let cursor = 0;

// 일반 전략들 (basic, jitter, jitter-swr, jitter-lock)
for (const strategy of strategies) {
  const sid = strategy.name.replace(/-/g, '_');

  // warmup
  options.scenarios[`warmup_${sid}`] = {
    executor: 'constant-arrival-rate',
    rate: WARMUP_RPS,
    timeUnit: '1s',
    duration: seconds(WARMUP_SEC),
    preAllocatedVUs: PREALLOCATED_VUS,
    maxVUs: MAX_VUS,
    startTime: seconds(cursor),
    exec: `warmup_${sid}`,
  };

  // wait for TTL expiry
  options.scenarios[`wait_${sid}`] = {
    executor: 'shared-iterations',
    vus: 1,
    iterations: 1,
    maxDuration: seconds(strategy.waitSeconds + 1),
    startTime: seconds(cursor + WARMUP_SEC),
    exec: `wait_${sid}`,
  };

  // burst
  options.scenarios[`burst_${sid}`] = {
    executor: 'constant-arrival-rate',
    rate: BURST_RPS,
    timeUnit: '1s',
    duration: seconds(BURST_SEC),
    preAllocatedVUs: PREALLOCATED_VUS,
    maxVUs: MAX_VUS,
    startTime: seconds(cursor + WARMUP_SEC + strategy.waitSeconds),
    exec: `burst_${sid}`,
  };

  // cooldown
  options.scenarios[`cooldown_${sid}`] = {
    executor: 'constant-arrival-rate',
    rate: COOLDOWN_RPS,
    timeUnit: '1s',
    duration: seconds(COOLDOWN_SEC),
    preAllocatedVUs: PREALLOCATED_VUS,
    maxVUs: MAX_VUS,
    startTime: seconds(cursor + WARMUP_SEC + strategy.waitSeconds + BURST_SEC),
    exec: `cooldown_${sid}`,
  };

  cursor += WARMUP_SEC + strategy.waitSeconds + BURST_SEC + COOLDOWN_SEC + GAP_SEC;
}

// full 전략 (soft TTL + hard TTL 2단계)
const FULL_SOFT_TTL_RATIO = envNumber('FULL_SOFT_TTL_RATIO', 0.9); // full 전용
const FULL_SOFT_TTL = BASE_TTL_SEC * FULL_SOFT_TTL_RATIO; // 18s
const FULL_WAIT_SOFT = Math.max(0, FULL_SOFT_TTL - WARMUP_SEC + 2); // 18 - 5 + 2 = 15s
const FULL_WAIT_HARD = Math.max(0, BASE_TTL_SEC - FULL_SOFT_TTL); // 20 - 18 = 2s
const FULL_BURST_SEC = envNumber('FULL_BURST_SEC', 10); // full 전용 burst 시간 (5s → 10s)

// warmup
options.scenarios.warmup_full = {
  executor: 'constant-arrival-rate',
  rate: WARMUP_RPS,
  timeUnit: '1s',
  duration: seconds(WARMUP_SEC),
  preAllocatedVUs: PREALLOCATED_VUS,
  maxVUs: MAX_VUS,
  startTime: seconds(cursor),
  exec: 'warmup_full',
};

// wait for soft TTL
options.scenarios.wait_soft_full = {
  executor: 'shared-iterations',
  vus: 1,
  iterations: 1,
  maxDuration: seconds(FULL_WAIT_SOFT + 1),
  startTime: seconds(cursor + WARMUP_SEC),
  exec: 'wait_soft_full',
};

// burst at soft TTL
options.scenarios.burst_soft_full = {
  executor: 'constant-arrival-rate',
  rate: BURST_RPS,
  timeUnit: '1s',
  duration: seconds(FULL_BURST_SEC),
  preAllocatedVUs: PREALLOCATED_VUS,
  maxVUs: MAX_VUS,
  startTime: seconds(cursor + WARMUP_SEC + FULL_WAIT_SOFT),
  exec: 'burst_soft_full',
};

// wait for hard TTL
options.scenarios.wait_hard_full = {
  executor: 'shared-iterations',
  vus: 1,
  iterations: 1,
  maxDuration: seconds(FULL_WAIT_HARD + 1),
  startTime: seconds(cursor + WARMUP_SEC + FULL_WAIT_SOFT + FULL_BURST_SEC),
  exec: 'wait_hard_full',
};

// burst at hard TTL
options.scenarios.burst_hard_full = {
  executor: 'constant-arrival-rate',
  rate: BURST_RPS,
  timeUnit: '1s',
  duration: seconds(FULL_BURST_SEC),
  preAllocatedVUs: PREALLOCATED_VUS,
  maxVUs: MAX_VUS,
  startTime: seconds(cursor + WARMUP_SEC + FULL_WAIT_SOFT + FULL_BURST_SEC + FULL_WAIT_HARD),
  exec: 'burst_hard_full',
};

// cooldown
options.scenarios.cooldown_full = {
  executor: 'constant-arrival-rate',
  rate: COOLDOWN_RPS,
  timeUnit: '1s',
  duration: seconds(COOLDOWN_SEC),
  preAllocatedVUs: PREALLOCATED_VUS,
  maxVUs: MAX_VUS,
  startTime: seconds(cursor + WARMUP_SEC + FULL_WAIT_SOFT + FULL_BURST_SEC + FULL_WAIT_HARD + FULL_BURST_SEC),
  exec: 'cooldown_full',
};

// 헬퍼 함수 - 전체 메트릭 (warmup/burst/cooldown 합산)
function makeRequest(strategy: StrategyName, endpoint: string) {
  const productId = pickProductId();
  const url = buildUrl(`/api/v1/products/${productId}/${endpoint}`);

  const res = http.get(url, {
    headers: { 'Content-Type': 'application/json' },
    tags: { strategy },
  });

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

  requestsByStrategy[strategy].add(1);
  durationMsByStrategy[strategy].add(res.timings.duration);
  errorsByStrategy[strategy].add(!ok);

  // DB 접근 추정 (responseTimeMs >= 90ms)
  try {
    const body = JSON.parse(res.body as string);
    if ((body?.meta?.responseTimeMs ?? 0) >= 90) {
      slowPathByStrategy[strategy].add(1);
    }
  } catch {
    // ignore
  }
}

// 헬퍼 함수 - burst 전용 메트릭
function makeRequestBurst(strategy: StrategyName, endpoint: string) {
  const productId = pickProductId();
  const url = buildUrl(`/api/v1/products/${productId}/${endpoint}`);

  const res = http.get(url, {
    headers: { 'Content-Type': 'application/json' },
    tags: { strategy, phase: 'burst' },
  });

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

  // 전체 메트릭
  requestsByStrategy[strategy].add(1);
  durationMsByStrategy[strategy].add(res.timings.duration);
  errorsByStrategy[strategy].add(!ok);

  // burst 전용 메트릭
  burstRequests[strategy].add(1);
  burstResponseTime[strategy].add(res.timings.duration);

  // DB 접근 추정
  try {
    const body = JSON.parse(res.body as string);
    if ((body?.meta?.responseTimeMs ?? 0) >= 90) {
      slowPathByStrategy[strategy].add(1);
      burstSlowPath[strategy].add(1);
    }
  } catch {
    // ignore
  }
}

// Full 전략용 soft burst 전용 함수
function makeRequestFullSoftBurst() {
  const productId = pickProductId();
  const url = buildUrl(`/api/v1/products/${productId}/full`);

  const res = http.get(url, {
    headers: { 'Content-Type': 'application/json' },
    tags: { strategy: 'full', phase: 'soft-burst' },
  });

  const ok = check(res, {
    'status is 200': (r: any) => r.status === 200,
    'has strategy meta': (r: any) => {
      try {
        const body = JSON.parse(r.body as string);
        return body?.meta?.strategy === 'full-protection';
      } catch {
        return false;
      }
    },
  });

  // 전체 + burst 메트릭
  requestsByStrategy['full'].add(1);
  durationMsByStrategy['full'].add(res.timings.duration);
  errorsByStrategy['full'].add(!ok);
  burstRequests['full'].add(1);
  burstResponseTime['full'].add(res.timings.duration);

  // soft burst 전용 메트릭
  fullSoftBurstRequests.add(1);
  fullSoftBurstResponseTime.add(res.timings.duration);

  try {
    const body = JSON.parse(res.body as string);
    if ((body?.meta?.responseTimeMs ?? 0) >= 90) {
      slowPathByStrategy['full'].add(1);
      burstSlowPath['full'].add(1);
      fullSoftBurstSlowPath.add(1);
    }
  } catch {
    // ignore
  }
}

// Full 전략용 hard burst 전용 함수
function makeRequestFullHardBurst() {
  const productId = pickProductId();
  const url = buildUrl(`/api/v1/products/${productId}/full`);

  const res = http.get(url, {
    headers: { 'Content-Type': 'application/json' },
    tags: { strategy: 'full', phase: 'hard-burst' },
  });

  const ok = check(res, {
    'status is 200': (r: any) => r.status === 200,
    'has strategy meta': (r: any) => {
      try {
        const body = JSON.parse(r.body as string);
        return body?.meta?.strategy === 'full-protection';
      } catch {
        return false;
      }
    },
  });

  // 전체 + burst 메트릭
  requestsByStrategy['full'].add(1);
  durationMsByStrategy['full'].add(res.timings.duration);
  errorsByStrategy['full'].add(!ok);
  burstRequests['full'].add(1);
  burstResponseTime['full'].add(res.timings.duration);

  // hard burst 전용 메트릭
  fullHardBurstRequests.add(1);
  fullHardBurstResponseTime.add(res.timings.duration);

  try {
    const body = JSON.parse(res.body as string);
    if ((body?.meta?.responseTimeMs ?? 0) >= 90) {
      slowPathByStrategy['full'].add(1);
      burstSlowPath['full'].add(1);
      fullHardBurstSlowPath.add(1);
    }
  } catch {
    // ignore
  }
}

// Export functions for each scenario
export function setup() {
  console.log('=== TTL Expiry-Based Cache Stampede Comparison ===');
  console.log(`BASE_TTL=${BASE_TTL_SEC}s, JITTER=${JITTER_MAX_SEC}s, SOFT_TTL=${SOFT_TTL_SEC}s`);
  console.log(`FULL: SOFT_TTL=${FULL_SOFT_TTL}s (ratio=${FULL_SOFT_TTL_RATIO})`);
  console.log(`BURST_RPS=${BURST_RPS}, BURST_SEC=${BURST_SEC}s, FULL_BURST_SEC=${FULL_BURST_SEC}s`);
  console.log('');
}

// basic
export function warmup_basic() { makeRequest('basic', 'basic'); }
export function wait_basic() { sleep(strategies[0].waitSeconds); console.log('basic: TTL expired, starting burst...'); }
export function burst_basic() { makeRequestBurst('basic', 'basic'); }
export function cooldown_basic() { makeRequest('basic', 'basic'); }

// jitter
export function warmup_jitter() { makeRequest('jitter', 'jitter'); }
export function wait_jitter() { sleep(strategies[1].waitSeconds); console.log('jitter: TTL expired, starting burst...'); }
export function burst_jitter() { makeRequestBurst('jitter', 'jitter'); }
export function cooldown_jitter() { makeRequest('jitter', 'jitter'); }

// jitter-swr
export function warmup_jitter_swr() { makeRequest('jitter-swr', 'jitter-swr'); }
export function wait_jitter_swr() { sleep(strategies[2].waitSeconds); console.log('jitter-swr: Soft TTL expired, starting burst...'); }
export function burst_jitter_swr() { makeRequestBurst('jitter-swr', 'jitter-swr'); }
export function cooldown_jitter_swr() { makeRequest('jitter-swr', 'jitter-swr'); }

// jitter-lock
export function warmup_jitter_lock() { makeRequest('jitter-lock', 'jitter-lock'); }
export function wait_jitter_lock() { sleep(strategies[3].waitSeconds); console.log('jitter-lock: TTL expired, starting burst...'); }
export function burst_jitter_lock() { makeRequestBurst('jitter-lock', 'jitter-lock'); }
export function cooldown_jitter_lock() { makeRequest('jitter-lock', 'jitter-lock'); }

// full
export function warmup_full() { makeRequest('full', 'full'); }
export function wait_soft_full() { sleep(FULL_WAIT_SOFT); console.log('full: Soft TTL expired, starting burst...'); }
export function burst_soft_full() { makeRequestFullSoftBurst(); }
export function wait_hard_full() { sleep(FULL_WAIT_HARD); console.log('full: Hard TTL expired, starting 2nd burst...'); }
export function burst_hard_full() { makeRequestFullHardBurst(); }
export function cooldown_full() { makeRequest('full', 'full'); }

export default function () {
  // Not used
}

export function handleSummary(data: any) {
  console.log('\n=== Cache Stampede Prevention Strategy Comparison ===\n');
  console.log('--- Burst Phase Results (TTL Expiry Stampede Only) ---\n');

  const allStrategies: StrategyName[] = ['basic', 'jitter', 'jitter-swr', 'jitter-lock', 'full'];

  allStrategies.forEach(s => {
    const burstKey = s.replace(/-/g, '_') + '_burst_response_time';
    const burstRt = data.metrics[burstKey];
    const burstSlowKey = s.replace(/-/g, '_') + '_burst_slow_path';
    const burstSlow = data.metrics[burstSlowKey];

    if (burstRt && burstRt.values) {
      console.log(`Strategy: ${s} (burst only)`);
      console.log(`  Avg Response Time: ${burstRt.values.avg?.toFixed(2)}ms`);
      console.log(`  P95 Response Time: ${burstRt.values['p(95)']?.toFixed(2)}ms`);
      console.log(`  DB Access: ${burstSlow?.values?.count ?? 0}`);
      console.log('');
    }
  });

  // Full 전략 soft/hard 분리
  console.log('--- Full Strategy Detail (Soft vs Hard TTL) ---\n');

  const fullSoft = data.metrics['full_soft_burst_response_time'];
  const fullSoftSlow = data.metrics['full_soft_burst_slow_path'];
  if (fullSoft && fullSoft.values) {
    console.log('Strategy: full (soft TTL burst)');
    console.log(`  Avg Response Time: ${fullSoft.values.avg?.toFixed(2)}ms`);
    console.log(`  P95 Response Time: ${fullSoft.values['p(95)']?.toFixed(2)}ms`);
    console.log(`  DB Access: ${fullSoftSlow?.values?.count ?? 0}`);
    console.log('');
  }

  const fullHard = data.metrics['full_hard_burst_response_time'];
  const fullHardSlow = data.metrics['full_hard_burst_slow_path'];
  if (fullHard && fullHard.values) {
    console.log('Strategy: full (hard TTL burst)');
    console.log(`  Avg Response Time: ${fullHard.values.avg?.toFixed(2)}ms`);
    console.log(`  P95 Response Time: ${fullHard.values['p(95)']?.toFixed(2)}ms`);
    console.log(`  DB Access: ${fullHardSlow?.values?.count ?? 0}`);
    console.log('');
  }

  console.log('Lower response times and fewer DB accesses = better stampede protection\n');

  return {
    'stdout': JSON.stringify(data, null, 2),
    'results/compare-strategies-summary.json': JSON.stringify(data),
  };
}
