import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import { buildUrl } from '../utils/config';
import { pickProductId } from '../utils/workload';

// K6 글로벌 변수
declare const __ENV: { [key: string]: string };

const strategies = [
  { name: 'no-cache', endpoint: 'no-cache' },
  { name: 'basic', endpoint: 'basic' },
  { name: 'jitter', endpoint: 'jitter' },
  { name: 'jitter-swr', endpoint: 'jitter-swr' },
  { name: 'jitter-lock', endpoint: 'jitter-lock' },
  { name: 'full', endpoint: 'full' },
] as const;

type StrategyName = typeof strategies[number]['name'];

function envNumber(name: string, fallback: number): number {
  const v = typeof __ENV !== 'undefined' ? __ENV[name] : undefined;
  if (!v) return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function seconds(n: number): string {
  return `${n}s`;
}

function safeId(name: string): string {
  // k6의 scenarios.exec는 "exports에 존재하는 함수명"이어야 하고,
  // JS 식별자 규칙을 따라야 한다. (하이픈 '-' 불가)
  return name.replace(/-/g, '_');
}

// "전략별 비교"는 한 VU가 전략을 순차 호출하면 부하가 분산되어 의미가 없다.
// -> 전략별로 독립 시나리오를 만들고, startTime으로 "순차 실행"하여 같은 조건에서 비교한다.
const durationMsByStrategy: Record<StrategyName, Trend> = {
  'no-cache': new Trend('no_cache_response_time', true),
  'basic': new Trend('basic_response_time', true),
  'jitter': new Trend('jitter_response_time', true),
  'jitter-swr': new Trend('jitter_swr_response_time', true),
  'jitter-lock': new Trend('jitter_lock_response_time', true),
  'full': new Trend('full_response_time', true),
};

const errorsByStrategy: Record<StrategyName, Rate> = {
  'no-cache': new Rate('no_cache_errors'),
  'basic': new Rate('basic_errors'),
  'jitter': new Rate('jitter_errors'),
  'jitter-swr': new Rate('jitter_swr_errors'),
  'jitter-lock': new Rate('jitter_lock_errors'),
  'full': new Rate('full_errors'),
};

const requestsByStrategy: Record<StrategyName, Counter> = {
  'no-cache': new Counter('no_cache_requests'),
  'basic': new Counter('basic_requests'),
  'jitter': new Counter('jitter_requests'),
  'jitter-swr': new Counter('jitter_swr_requests'),
  'jitter-lock': new Counter('jitter_lock_requests'),
  'full': new Counter('full_requests'),
};

function makeExec(strategy: StrategyName, endpoint: string) {
  return function () {
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
          // full endpoint는 meta.strategy가 "full-protection"으로 내려옴
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

    // 부하를 충분히 만들기 위해 think time은 최소화 (필요 시 ENV로 조절 가능)
    sleep(envNumber('THINK_TIME_SEC', 0));
  };
}

export const options = {
  scenarios: {
    // 각 전략을 순차로 실행 (서버 상태/캐시 상태를 동일하게 맞추기 쉬움)
  },
};

const warmupSec = envNumber('WARMUP_SEC', 10);
const runSec = envNumber('RUN_SEC', 30);
const rps = envNumber('RPS', 300);
const preAllocatedVUs = envNumber('PREALLOCATED_VUS', 200);
const maxVUs = envNumber('MAX_VUS', 2000);
const gapSec = envNumber('GAP_SEC', 5);

let cursor = 0;
for (const s of strategies) {
  const sid = safeId(s.name);
  // 매 전략 시작 전 "캐시 무효화"를 트리거해 동일한 시작 상태로 맞춘다.
  // (invalidate는 모든 전략을 지우지만, 서버 코드에서 전략별 캐시 키를 분리하면 비교가 성립한다)
  const resetName = `reset_${sid}`;
  // @ts-ignore
  options.scenarios[resetName] = {
    executor: 'per-vu-iterations',
    vus: 1,
    iterations: 1,
    startTime: seconds(cursor),
    exec: resetName,
  };

  const runName = `run_${sid}`;
  // @ts-ignore
  options.scenarios[runName] = {
    executor: 'constant-arrival-rate',
    rate: rps,
    timeUnit: '1s',
    duration: seconds(runSec),
    preAllocatedVUs,
    maxVUs,
    startTime: seconds(cursor + warmupSec),
    exec: runName,
  };

  cursor += warmupSec + runSec + gapSec;
}

export function setup() {
  // compare에서는 별도 setup 필요 없음 (각 전략 시작 직전에 reset을 수행)
}

function resetCache() {
  const hotKeyId = envNumber('HOT_KEY_ID', 1);
  const url = buildUrl(`/api/v1/products/${hotKeyId}/cache`);
  http.del(url);
}

// reset_* 함수들 (k6 exec 연결용)
export function reset_no_cache() { resetCache(); }
export function reset_basic() { resetCache(); }
export function reset_jitter() { resetCache(); }
export function reset_jitter_swr() { resetCache(); }
export function reset_jitter_lock() { resetCache(); }
export function reset_full() { resetCache(); }

// run_* 함수들 (k6 exec 연결용)
export const run_no_cache = makeExec('no-cache', 'no-cache');
export const run_basic = makeExec('basic', 'basic');
export const run_jitter = makeExec('jitter', 'jitter');
export const run_jitter_swr = makeExec('jitter-swr', 'jitter-swr');
export const run_jitter_lock = makeExec('jitter-lock', 'jitter-lock');
export const run_full = makeExec('full', 'full');

export default function () {
  // default는 사용하지 않음 (scenarios.exec로만 실행)
}

export function handleSummary(data: any) {
  console.log('\n=== Cache Stampede Prevention Strategy Comparison ===\n');

  // 전략별 결과 요약 출력
  strategies.forEach(s => {
    const key = s.name.replace(/-/g, '_') + '_response_time';
    const rt = data.metrics[key];

    if (rt && rt.values) {
      console.log(`Strategy: ${s.name}`);
      console.log(`  Avg Response Time: ${rt.values.avg?.toFixed(2)}ms`);
      console.log(`  P95 Response Time: ${rt.values['p(95)']?.toFixed(2)}ms`);
      console.log('');
    }
  });

  return {
    'stdout': JSON.stringify(data, null, 2),
    'results/compare-strategies-summary.json': JSON.stringify(data),
  };
}
