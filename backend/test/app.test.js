'use strict';

const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const { createApp } = require('../src/app');
const { IncidentStore } = require('../src/store');

let server;
let base;
const deviceId = '3f1c2b9e-4a6d-4c1b-9f2e-7a8b9c0d1e2f';

before(async () => {
  server = createApp().listen(0);
  await new Promise((resolve) => server.once('listening', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

const post = (path, body) =>
  fetch(base + path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

test('GET /health reports ok', async () => {
  const res = await fetch(`${base}/health`);
  assert.equal(res.status, 200);
  const json = await res.json();
  assert.equal(json.status, 'ok');
  assert.equal(json.service, 'SenseConnect');
  assert.ok(typeof json.uptimeSeconds === 'number');
});

test('GET /api/v1/config returns emergency numbers', async () => {
  const json = await (await fetch(`${base}/api/v1/config`)).json();
  assert.equal(json.defaultEmergencyNumber, '112');
  assert.equal(json.emergencyNumbers.IN, '112');
  assert.equal(json.emergencyNumbers.US, '911');
});

test('incident lifecycle: create, read (owner only), resolve', async () => {
  const created = await post('/api/v1/incidents', {
    deviceId,
    triggeredAt: Date.now(),
    location: { lat: 12.9716, lng: 77.5946, accuracy: 14.5 },
    contactConfigured: true,
    appVersion: '2.0.0',
  });
  assert.equal(created.status, 201);
  const incident = await created.json();
  assert.equal(incident.status, 'active');
  assert.equal(incident.location.lat, 12.9716);
  assert.equal(incident.deviceId, undefined, 'device id must not be echoed');

  const stranger = await fetch(`${base}/api/v1/incidents/${incident.id}?deviceId=00000000-0000-4000-8000-000000000000`);
  assert.equal(stranger.status, 404);

  const owner = await fetch(`${base}/api/v1/incidents/${incident.id}?deviceId=${deviceId}`);
  assert.equal(owner.status, 200);

  const resolved = await post(`/api/v1/incidents/${incident.id}/resolve`, { deviceId });
  assert.equal(resolved.status, 200);
  assert.equal((await resolved.json()).status, 'resolved');
});

test('incident without location is accepted', async () => {
  const res = await post('/api/v1/incidents', { deviceId, triggeredAt: Date.now() });
  assert.equal(res.status, 201);
  assert.equal((await res.json()).location, null);
});

test('invalid incidents are rejected', async () => {
  assert.equal((await post('/api/v1/incidents', { deviceId: 'nope', triggeredAt: Date.now() })).status, 400);
  assert.equal((await post('/api/v1/incidents', { deviceId, triggeredAt: 'yesterday' })).status, 400);
  assert.equal((await post('/api/v1/incidents', { deviceId, triggeredAt: Date.now(), location: { lat: 200, lng: 0 } })).status, 400);
  const badJson = await fetch(`${base}/api/v1/incidents`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{oops' });
  assert.equal(badJson.status, 400);
});

test('unknown routes return JSON 404', async () => {
  const res = await fetch(`${base}/does-not-exist`);
  assert.equal(res.status, 404);
  assert.equal((await res.json()).error, 'Not found');
});

test('store expires incidents after the retention window', () => {
  let now = 1_000_000;
  const store = new IncidentStore({ ttlMs: 1000, now: () => now });
  const incident = store.create({ deviceId, triggeredAt: now });
  assert.ok(store.get(incident.id));
  now += 1001;
  assert.equal(store.get(incident.id), null);
});
