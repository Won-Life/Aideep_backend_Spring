const editor = document.querySelector('#event');
const status = document.querySelector('#status');
const response = document.querySelector('#response');
const buttons = document.querySelectorAll('button');

function sample(kind) {
    if (kind === 'invalid') {
        editor.value = '{';
        return;
    }
    editor.value = JSON.stringify({
        version: kind === 'version' ? 99 : 1,
        eventId: crypto.randomUUID(),
        eventType: kind === 'patch' ? 'NODE_PATCH_REQUESTED' : 'NODE_CREATE_REQUESTED',
        occurredAt: new Date().toISOString(),
        workspaceId: '22222222-2222-4222-8222-222222222222',
        payload: kind === 'patch' ? {
            nodeId: '55555555-5555-4555-8555-555555555555',
            expectedVersion: 1,
            patch: { title: '수정 테스트' },
        } : {
            title: 'Redis Stream 테스트',
            nodeType: 'DATA',
            position: { x: 100, y: 200 },
            data: {
                dataType: 'MARKDOWN', markdownBody: '# 테스트', jsonBody: '{}',
                color: '#ffffff', textColor: '#000000',
            },
        },
    }, null, 2);
}

async function request(path, body) {
    buttons.forEach(button => { button.disabled = true; });
    status.className = '';
    status.textContent = '요청 중…';
    try {
        const result = await fetch(path, {
            method: body === undefined ? 'GET' : 'POST',
            ...(body === undefined ? {} : {
                headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
            }),
            signal: AbortSignal.timeout(10000),
        });
        const text = await result.text();
        try { response.textContent = JSON.stringify(JSON.parse(text), null, 2); }
        catch { response.textContent = text; }
        status.textContent = `${path} · HTTP ${result.status} · ${new Date().toLocaleTimeString()}`;
        if (!result.ok) status.className = 'error';
    } catch (error) {
        status.className = 'error';
        status.textContent = '요청 실패: 서버 연결 또는 응답 시간을 확인하세요.';
        response.textContent = error.message;
    } finally {
        buttons.forEach(button => { button.disabled = false; });
    }
}

document.querySelectorAll('[data-sample]').forEach(button => {
    button.addEventListener('click', () => sample(button.dataset.sample));
});
document.querySelectorAll('[data-path]').forEach(button => {
    button.addEventListener('click', () => request(button.dataset.path));
});
document.querySelector('#publish').addEventListener('click', () => {
    request('/events/raw', { data: editor.value });
});
sample('create');
