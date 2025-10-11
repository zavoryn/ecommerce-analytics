// k6 压测脚本: 趋势查询接口 (query :8083 / gateway :8080)
//
// 测试目标:
//   - 模拟运营后台并发刷趋势报表
//   - P95 < 100ms (走聚合表+多级缓存)
//   - 错误率 < 1%
//
// 关键: 趋势查询接口受 JWT 鉴权保护, 需要 Authorization Bearer
//      使用 dev secret 在本地预签发一个 token, 见 README.md
//
// 运行: k6 run --env GATEWAY=http://localhost:8080 --env TOKEN=<jwt> query-trend.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomIntBetween } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
    scenarios: {
        steady: {
            executor: 'constant-vus',
            vus: 50,
            duration: '60s',
        },
    },
    thresholds: {
        // baseline: 查询走聚合表 + 多级缓存, P95 < 100ms
        'http_req_duration': ['p(95)<100', 'p(99)<300'],
        'http_req_failed':   ['rate<0.01'],
        'checks':            ['rate>0.99'],
    },
};

const GATEWAY = __ENV.GATEWAY || 'http://localhost:8080';
const TOKEN   = __ENV.TOKEN   || '';

const COMMON_ITEM_IDS = [101, 202, 303, 404, 888, 1234];

export default function () {
    const itemId = COMMON_ITEM_IDS[Math.floor(Math.random() * COMMON_ITEM_IDS.length)];
    const days = randomIntBetween(7, 30);

    const res = http.get(`${GATEWAY}/api/query/item-trend?itemId=${itemId}&days=${days}`, {
        headers: {
            'Authorization': `Bearer ${TOKEN}`,
            'X-Trace-Id': `perf-${__VU}-${__ITER}`,
        },
        tags: { endpoint: 'query-trend' },
    });

    check(res, {
        'status is 200': (r) => r.status === 200,
        'response code = 0': (r) => {
            try { return r.json('code') === 0; }
            catch { return false; }
        },
    });

    sleep(Math.random() * 0.5 + 0.2);
}
