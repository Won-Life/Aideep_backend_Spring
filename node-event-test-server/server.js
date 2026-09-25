import {randomUUID} from 'node:crypto';
import {fileURLToPath} from 'node:url';
import express from 'express';
import {createClient} from 'redis';

const stream = process.env.NODE_EVENTS_STREAM_KEY || 'onnode:ai:commands:v1';
const dlq = process.env.NODE_EVENTS_DLQ_STREAM_KEY || 'onnode:ai:commands:dlq:v1';
const group = process.env.NODE_EVENTS_CONSUMER_GROUP || 'aideep-node-command-workers-v1';
const redisClient = createClient({
    url: process.env.REDIS_URL || 'redis://192.168.35.89:6400',
    username: process.env.REDIS_USERNAME || undefined,
    password: process.env.REDIS_PASSWORD || undefined,
    disableOfflineQueue: true,
});
redisClient.on('error', () => console.error('Redis connection error; check local Redis settings.'));
await redisClient.connect();

const app = express();
app.use(express.json());
app.use(express.static(fileURLToPath(new URL('./public', import.meta.url))));

app.get('/health', async (req, res) => {
    res.json({redis: await redisClient.ping(), stream, group});
});

// Empty body publishes a sample; supplied envelope fields override the sample.
app.post('/events', async (req, res) => {
    const body = req.body ?? {};
    const patch = body.eventType === 'NODE_PATCH_REQUESTED';
    const event = {
        version: 1,
        eventId: randomUUID(),
        eventType: 'NODE_CREATE_REQUESTED',
        occurredAt: new Date().toISOString(),
        workspaceId: '422e16a9-fc48-4c0f-b2fc-1d8b94e2792a',
        payload: patch ? {
            nodeId: '55555555-5555-4555-8555-555555555555',
            expectedVersion: 1,
            patch: {title: '수정 테스트'},
        } : {
            title: 'Redis Stream 테스트',
            nodeType: 'DATA',
            position: {x: 100, y: 200},
            data: {
                dataType: 'MARKDOWN', markdownBody: '# 테스트',
                jsonBody: '{}', color: '#ffffff', textColor: '#000000',
            },
        },
        ...body,
    };
    const entryId = await redisClient.xAdd(stream, '*', {data: JSON.stringify(event)});
    res.status(201).json({entryId, event});
});

// Send malformed JSON inside data to exercise the backend DLQ path.
app.post('/events/raw', async (req, res) => {
    if (typeof req.body?.data !== 'string') {
        return res.status(400).json({error: 'data must be a string'});
    }
    const entryId = await redisClient.xAdd(stream, '*', {data: req.body.data});
    res.status(201).json({entryId});
});

app.get('/pending', async (req, res) => {
    try {
        const entries = await redisClient.sendCommand(['XPENDING', stream, group, '-', '+', '50']);
        res.json({
            group, entries: entries.map(([entryId, consumer, idleMs, deliveries]) =>
                ({entryId, consumer, idleMs, deliveries}))
        });
    } catch (error) {
        if (error.message.startsWith('NOGROUP')) {
            return res.status(409).json({error: 'Consumer group missing. Start the Spring backend first.'});
        }
        throw error;
    }
});

app.get('/dlq', async (req, res) => {
    res.json(await redisClient.xRevRange(dlq, '+', '-', {COUNT: 50}));
});

app.use((error, req, res, next) => {
    const invalidJson = error.type === 'entity.parse.failed';
    res.status(invalidJson ? 400 : 500).json({
        error: invalidJson ? 'Invalid request JSON' : 'Request failed; check Redis connection and key types.',
    });
});

const server = app.listen(Number(process.env.PORT || 3001), '127.0.0.1', () => {
    console.log(`Node event test server: http://127.0.0.1:${server.address().port}`);
});
for (const signal of ['SIGINT', 'SIGTERM']) {
    process.once(signal, () => server.close(() => redisClient.destroy()));
}
