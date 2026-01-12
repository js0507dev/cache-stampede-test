// K6 글로벌 변수
declare const __ENV: { [key: string]: string };

function envNumber(name: string, fallback: number): number {
  const v = typeof __ENV !== 'undefined' ? __ENV[name] : undefined;
  if (v === undefined || v === '') return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function envString(name: string, fallback: string): string {
  const v = typeof __ENV !== 'undefined' ? __ENV[name] : undefined;
  return v && v.length > 0 ? v : fallback;
}

function parseIdList(csv: string): number[] {
  return csv
    .split(',')
    .map(s => s.trim())
    .filter(Boolean)
    .map(s => Number(s))
    .filter(n => Number.isFinite(n));
}

export const workload = {
  // 스탬피드는 "핫 키(Hot key)"에 트래픽이 몰릴 때 발생
  hotKeyId: envNumber('HOT_KEY_ID', 1),
  hotKeyRatio: envNumber('HOT_KEY_RATIO', 0.95), // 95%를 hot key로
  coldKeyIds: parseIdList(envString('COLD_KEY_IDS', '2,3')),
};

export function pickProductId(): number {
  const r = Math.random();
  if (r < workload.hotKeyRatio) return workload.hotKeyId;

  if (workload.coldKeyIds.length === 0) return workload.hotKeyId;
  const idx = Math.floor(Math.random() * workload.coldKeyIds.length);
  return workload.coldKeyIds[idx];
}

