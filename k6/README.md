# K6 Load Tests for Cache Stampede Prevention

## Overview

TTL 만료 기반 캐시 스탬피드 비교 테스트입니다. 각 전략별로 캐시 만료 시점에 burst를 발생시켜 stampede 방지 효과를 비교합니다.

## Test Scenarios

### compare-strategies.ts

각 전략별로 **TTL 만료 시점**에 맞춰 burst를 발생시킵니다:

- **basic**: Hard TTL (20s) 만료 시 stampede 발생
- **jitter**: Hard TTL + random jitter (20~25s) 만료 시
- **jitter-swr**: **Soft TTL (10s) 만료 시** → stale 즉시 반환
- **jitter-lock**: Hard TTL 만료 + 분산 락으로 stampede 방지
- **full**: Soft TTL (18s) + Hard TTL (20s) 2단계 burst

## 실행 방법

### 1. 기본 비교 테스트

```bash
# 서버 실행 (기본 설정)
./gradlew bootRun

# k6 테스트 실행
cd k6
yarn run test:compare
```

**예상 시간**: 약 4분

---

### 2. Full 전략 테스트 (Soft + Hard TTL 모두 관찰)

Full 전략은 **soft TTL과 hard TTL 간격을 좁혀**(0.9 ratio) 두 가지 상황을 모두 관찰합니다.

#### 서버 재시작 (soft-ttl-ratio 0.9로 설정)

```bash
# 터미널 1: 서버 실행 (환경변수로 soft-ttl-ratio 오버라이드)
CACHE_STAMPEDE_SOFT_TTL_RATIO=0.9 ./gradlew bootRun
```

#### k6 테스트 실행

```bash
# 터미널 2: k6 테스트 (full 전략용 soft-ttl-ratio 지정)
cd k6
FULL_SOFT_TTL_RATIO=0.9 yarn run test:compare
```

**결과**:
- **1차 burst (soft TTL 18s)**: stale 반환 + 백그라운드 갱신
- **2차 burst (hard TTL 20s)**: 동기 갱신 + 락 경합 (2초 후)

---

## 환경변수

### 서버 (Spring Boot)

| 환경변수 | 기본값 | 설명 |
|---------|--------|------|
| `CACHE_STAMPEDE_BASE_TTL_SECONDS` | 20 | 기본 TTL (초) |
| `CACHE_STAMPEDE_JITTER_MAX_SECONDS` | 5 | 최대 jitter (초) |
| `CACHE_STAMPEDE_SOFT_TTL_RATIO` | 0.5 | Soft TTL 비율 |
| `CACHE_STAMPEDE_LOCK_TIMEOUT_SECONDS` | 5 | 락 타임아웃 |
| `CACHE_STAMPEDE_LOCK_MAX_RETRIES` | 10 | 락 최대 재시도 |

### k6 테스트

| 환경변수 | 기본값 | 설명 |
|---------|--------|------|
| `BASE_TTL_SEC` | 20 | 서버 base-ttl-seconds와 일치 |
| `JITTER_MAX_SEC` | 5 | 서버 jitter-max-seconds와 일치 |
| `SOFT_TTL_RATIO` | 0.5 | 서버 soft-ttl-ratio와 일치 |
| `FULL_SOFT_TTL_RATIO` | 0.9 | **Full 전략 전용** soft-ttl-ratio |
| `WARMUP_RPS` | 50 | Warmup 단계 RPS |
| `BURST_RPS` | 1000 | Burst 단계 RPS |
| `BURST_SEC` | 10 | Burst 지속 시간 |

---

## 시나리오 구조

각 전략별로 4단계로 구성:

```
[warmup] → [wait for expiry] → [burst] → [cooldown]
   5s          전략별 가변          10s        10s
```

### 전략별 대기 시간

| 전략 | 대기 시간 | 만료 시점 |
|------|----------|----------|
| basic | 17s | Hard TTL (20s) |
| jitter | 22s | Hard TTL + max jitter (25s) |
| jitter-swr | 7s | **Soft TTL (10s)** |
| jitter-lock | 22s | Hard TTL + max jitter (25s) |
| full | 15s + 2s | Soft (18s) + Hard (20s) |

---

## 메트릭

각 전략별로 다음 메트릭을 수집:

- `{strategy}_response_time`: 응답 시간 (avg, p95)
- `{strategy}_slow_path`: DB 접근 횟수 (≥90ms)
- `{strategy}_requests`: 총 요청 수
- `{strategy}_errors`: 에러 비율

**Good stampede protection**: 낮은 응답 시간 + 적은 DB 접근

---

## 예상 결과

| 전략 | P95 응답 시간 | DB 접근 |
|------|---------------|---------|
| basic | 높음 (>100ms) | 많음 (stampede 발생) |
| jitter | 중간 | 중간 (TTL 분산) |
| jitter-swr | **낮음 (<10ms)** | **적음** (stale 반환) |
| jitter-lock | 낮음 | 적음 (락으로 방지) |
| full | **매우 낮음** | **매우 적음** (SWR + Lock) |

---

## 기타 테스트

### Stampede Simulation (단일 전략 집중 테스트)

```bash
# Invalidate 기반 (캐시 무효화 후 burst)
STRATEGY=jitter-swr yarn run test:stampede:jitter-swr

# TTL-expiry 기반 (TTL 만료 후 burst)
MODE=ttl-expiry STRATEGY=jitter-swr yarn run test:stampede:jitter-swr
```

### 개별 전략 테스트

```bash
yarn run test:basic
yarn run test:jitter
yarn run test:jitter-swr
yarn run test:jitter-lock
yarn run test:full
```
