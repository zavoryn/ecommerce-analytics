// k6 压测脚本: TOP 商品排行榜接口 (query /api/ranking/top-items)
//
// 测试目标:
//   - 验证多级缓存(Caffeine L1 + Redis L2)在高并发下的命中率
//   - P95 < 50ms (L1 命中后基本不打 DB)
//   - 同一组热点 key 命中率应趋于 100%
//
// 运行: k6 run --env GATEWAY=http://localhost:8080 --env TOKEN=<jwt> ranking-top.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const cacheLatency = new Trend('cache_path_latency_ms');

export const options = {
    scenarios: {
        ramp: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '10s', target: 50 },
                { duration: '60s', target: 200 },   // 200 并发模拟运营高峰
                { duration: '10s', target: 0 },
            ],
        },
    },
    thresholds: {
        // baseline: 走 Caffeine L1, P95 应 < 50ms; P99 < 150ms
        'http_req_duration': ['p(95)<50', 'p(99)<150'],
        'http_req_failed':   ['rate<0.01'],
    },
};

const GATEWAY = __ENV.GATEWAY || 'http://localhost:8080';
const TOKEN   = __ENV.TOKEN   || '';

// 故意让 key 集中, 测试缓存命中率
const RANK_BY = ['gmv', 'pay_cnt', 'pv'];
const DAYS    = [7, 30];

export default function () {
    const rankBy = RANK_BY[Math.floor(Math.random() * RANK_BY.length)];
    const days   = DAYS[Math.floor(Math.random() * DAYS.length)];

    const start = Date.now();
    const res = http.get(
        `${GATEWAY}/api/ranking/top-items?rankBy=${rankBy}&days=${days}&limit=20`,
        {
            headers: {
                'Authorization': `Bearer ${TOKEN}`,
                'X-Trace-Id': `perf-${__VU}-${__ITER}`,
            },
            tags: { endpoint: 'ranking-top', rankBy },
        }
    );
    cacheLatency.add(Date.now() - start);

    check(res, {
        'status is 200': (r) => r.status === 200,
        'response code = 0': (r) => {
            try { return r.json('code') === 0; }
            catch { return false; }
        },
    });

    sleep(0.1);
}
