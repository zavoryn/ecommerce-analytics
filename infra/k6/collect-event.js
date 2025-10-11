// k6 压测脚本: 埋点上报接口 (collector :8081 / gateway :8080)
//
// 测试目标:
//   - 验证埋点接口在 100 并发持续 1min 下 P95 < 200ms / P99 < 500ms
//   - 错误率 < 1%
//
// 运行:
//   k6 run --env GATEWAY=http://localhost:8080 collect-event.js
//   k6 run --env GATEWAY=http://localhost:8080 --out json=results.json collect-event.js
//
// CI 集成: 见 .github/workflows/perf.yml
import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomString, randomItem } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export const options = {
    scenarios: {
        ramp_then_steady: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '15s', target: 50 },   // 0  → 50  并发
                { duration: '15s', target: 100 },  // 50 → 100 并发
                { duration: '60s', target: 100 },  // 100 持续 1 min
                { duration: '10s', target: 0 },    // ramp-down
            ],
            gracefulRampDown: '5s',
        },
    },
    thresholds: {
        // baseline: 埋点接口 P95 < 200ms, P99 < 500ms
        'http_req_duration': ['p(95)<200', 'p(99)<500'],
        // 错误率 < 1%
        'http_req_failed':   ['rate<0.01'],
        // 自定义业务校验通过率 > 99%
        'checks':            ['rate>0.99'],
    },
};

const GATEWAY = __ENV.GATEWAY || 'http://localhost:8080';

const EVENT_NAMES = ['view_item', 'add_cart', 'search', 'click', 'pay_order'];
const CATEGORIES  = ['服饰', '美妆', '家电', '食品', '数码'];

export default function () {
    const payload = JSON.stringify({
        requestId: `req-${__VU}-${__ITER}-${randomString(8)}`,
        deviceId: `dev-${__VU}-${randomString(6)}`,
        sessionId: `sess-${__VU}`,
        eventName: randomItem(EVENT_NAMES),
        timestamp: Date.now(),
        os: 'iOS',
        appVersion: '6.7.0',
        network: 'wifi',
        properties: {
            item_id: Math.floor(Math.random() * 10000),
            category: randomItem(CATEGORIES),
            price: (Math.random() * 1000).toFixed(2),
        },
    });

    const res = http.post(`${GATEWAY}/api/collect/event`, payload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { endpoint: 'collect-event' },
    });

    check(res, {
        'status is 200': (r) => r.status === 200,
        'response code = 0': (r) => {
            try { return r.json('code') === 0; }
            catch { return false; }
        },
    });

    sleep(Math.random() * 0.3);   // 模拟真实流量, 0-300ms 间隔
}
