# Cache Stampede Prevention Sample Project

Spring Boot + Kotlin 기반 캐시 스탬피드 방지 전략 샘플 프로젝트

## 기술 스택

| 구분 | 기술 | 버전 |
|------|------|------|
| Language | Kotlin | 1.9.x |
| Framework | Spring Boot | 3.2.x |
| Build | Gradle | 8.7 |
| Database | PostgreSQL | 16 |
| Cache | Redis | 7.x |
| Test | Kotest | 5.8.x |
| Load Test | K6 | TypeScript |

## 프로젝트 구조

```
├── src/main/kotlin/com/example/cachestampede/
│   ├── domain/product/           # 도메인 모델
│   ├── application/product/      # 비즈니스 로직
│   ├── infrastructure/cache/     # 캐시 전략 구현
│   │   ├── strategy/            # 5가지 캐시 전략
│   │   └── lock/                # 분산 락
│   └── interfaces/api/          # REST API
├── src/test/                    # Kotest 테스트
├── k6/                          # K6 로드 테스트
└── docker-compose.yml
```

## 캐시 스탬피드 방지 전략

### 1. 기본 캐시 (스탬피드 대응 X)
- 단순 캐시 조회/저장
- 캐시 만료 시 모든 요청이 동시에 DB 접근

### 2. TTL Jitter
- 캐시 TTL에 랜덤 값 추가 (0~10초)
- 만료 시점 분산으로 동시 만료 방지

### 3. TTL Jitter + SWR (Stale-While-Revalidate)
- Soft TTL: 갱신 시작 시점 (기본 TTL의 80%)
- Hard TTL: 반드시 갱신 필요 시점
- Stale 데이터 반환 후 백그라운드 갱신

### 4. TTL Jitter + Distributed Lock
- 캐시 미스 시 분산 락으로 동시 DB 접근 제한
- 하나의 요청만 DB 접근, 나머지는 대기 후 캐시에서 조회

### 5. TTL Jitter + SWR + Lock (전체 보호)
- 모든 전략 조합
- 가장 강력한 스탬피드 방지

## API 엔드포인트

| 엔드포인트 | 전략 | 설명 |
|------------|------|------|
| `GET /api/v1/products/{id}/no-cache` | 없음 | DB 직접 조회 |
| `GET /api/v1/products/{id}/basic` | 기본 캐시 | 스탬피드 대응 X |
| `GET /api/v1/products/{id}/jitter` | TTL Jitter | TTL 분산 |
| `GET /api/v1/products/{id}/jitter-swr` | Jitter + SWR | 백그라운드 갱신 |
| `GET /api/v1/products/{id}/jitter-lock` | Jitter + Lock | 분산 락 |
| `GET /api/v1/products/{id}/full` | 전체 보호 | 모든 전략 |

## 실행 방법

### 1. 인프라 실행
```bash
docker-compose up -d
```

### 2. 애플리케이션 실행
```bash
./gradlew bootRun
```

### 3. 테스트 데이터 생성
```bash
curl -X POST http://localhost:8080/api/v1/admin/products/bulk?count=10
```

### 4. API 테스트
```bash
# 캐시 없음
curl http://localhost:8080/api/v1/products/1/no-cache

# 전체 보호 전략
curl http://localhost:8080/api/v1/products/1/full
```

## 로드 테스트 (K6)

### 설정
```bash
cd k6
npm install
```

### 전략별 테스트
```bash
npm run test:no-cache
npm run test:basic
npm run test:jitter
npm run test:jitter-swr
npm run test:jitter-lock
npm run test:full
```

### 전략 비교 테스트
```bash
npm run test:compare
```

## 단위 테스트

```bash
./gradlew test
```

## 설정 옵션

`application.yml`에서 캐시 설정 변경 가능:

```yaml
cache:
  stampede:
    base-ttl-seconds: 60        # 기본 TTL
    jitter-max-seconds: 10      # Jitter 최대값
    soft-ttl-ratio: 0.8         # Soft TTL 비율 (SWR용)
    lock-timeout-seconds: 5     # 락 타임아웃
    lock-retry-interval-ms: 50  # 락 재시도 간격
    lock-max-retries: 100       # 락 최대 재시도
```
