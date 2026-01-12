// K6 글로벌 변수
declare const __ENV: { [key: string]: string };

export const config = {
  baseUrl: (typeof __ENV !== 'undefined' && __ENV.BASE_URL) || 'http://localhost:8080',

  // 테스트 상품 ID (Hot Key 시뮬레이션용)
  productIds: [1, 2, 3],

  // 기본 테스트 설정
  defaultOptions: {
    vus: 100,           // 동시 사용자 수
    duration: '30s',     // 테스트 시간
  },

  // 스탬피드 시뮬레이션 설정 (캐시 만료 시점에 동시 요청)
  stampedeOptions: {
    vus: 200,
    duration: '60s',
  },

  // 점진적 증가 테스트 설정
  rampingOptions: {
    stages: [
      { duration: '10s', target: 50 },   // 10초 동안 50 VU로 증가
      { duration: '30s', target: 100 },  // 30초 동안 100 VU 유지
      { duration: '20s', target: 200 },  // 20초 동안 200 VU로 증가
      { duration: '30s', target: 200 },  // 30초 동안 200 VU 유지
      { duration: '10s', target: 0 },    // 10초 동안 0으로 감소
    ],
  },

  // 성공 기준 (Thresholds)
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],  // 95%ile < 500ms, 99%ile < 1000ms
    http_req_failed: ['rate<0.01'],                   // 에러율 < 1%
  },
};

export function getRandomProductId(): number {
  const index = Math.floor(Math.random() * config.productIds.length);
  return config.productIds[index];
}

export function buildUrl(endpoint: string): string {
  return `${config.baseUrl}${endpoint}`;
}
