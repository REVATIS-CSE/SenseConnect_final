'use strict';

const express = require('express');
const { IncidentStore, toPublic } = require('./store');
const pkg = require('../package.json');

const SERVICE = 'SenseConnect';
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Default emergency numbers by ISO country code; the app falls back to 112 (GSM standard). */
const EMERGENCY_NUMBERS = {
  IN: '112', US: '911', CA: '911', MX: '911', GB: '999', IE: '112', AU: '000', NZ: '111',
  DE: '112', FR: '112', ES: '112', IT: '112', NL: '112', SG: '995', AE: '999', JP: '110',
  ZA: '10111', BR: '190', LK: '119', BD: '999', PK: '15', NP: '100',
};

/** Tiny fixed-window rate limiter (per IP) so the incident endpoint can't be flooded. */
function rateLimit({ windowMs, max }) {
  const hits = new Map();
  return (req, res, next) => {
    const now = Date.now();
    const key = req.ip;
    const entry = hits.get(key);
    if (!entry || now - entry.start > windowMs) {
      hits.set(key, { start: now, count: 1 });
      if (hits.size > 10_000) hits.clear();
      return next();
    }
    entry.count += 1;
    if (entry.count > max) {
      res.set('Retry-After', String(Math.ceil((entry.start + windowMs - now) / 1000)));
      return res.status(429).json({ error: 'Too many requests, please slow down.' });
    }
    return next();
  };
}

function validLocation(loc) {
  if (loc === undefined || loc === null) return true;
  if (typeof loc !== 'object') return false;
  const { lat, lng, accuracy } = loc;
  if (typeof lat !== 'number' || lat < -90 || lat > 90) return false;
  if (typeof lng !== 'number' || lng < -180 || lng > 180) return false;
  if (accuracy !== undefined && accuracy !== null && (typeof accuracy !== 'number' || accuracy < 0)) return false;
  return true;
}

function createApp({ store = new IncidentStore(), startedAt = Date.now(), announcement = process.env.ANNOUNCEMENT || null } = {}) {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 1); // Render terminates TLS at its proxy
  app.use(express.json({ limit: '16kb' }));

  app.use((req, res, next) => {
    res.set('Cache-Control', 'no-store');
    res.set('X-Content-Type-Options', 'nosniff');
    next();
  });

  app.get('/', (req, res) => {
    res.json({
      service: SERVICE,
      motto: 'One Device. Three Disabilities.',
      version: pkg.version,
      endpoints: ['GET /health', 'GET /api/v1/config', 'POST /api/v1/incidents', 'GET /api/v1/incidents/:id', 'POST /api/v1/incidents/:id/resolve', 'GET /api/v1/stats'],
    });
  });

  // ---- Health: used by the app's Service Status panel and by Render's health check.
  app.get('/health', (req, res) => {
    res.json({
      status: 'ok',
      service: SERVICE,
      version: pkg.version,
      uptimeSeconds: Math.round((Date.now() - startedAt) / 1000),
      timestamp: new Date().toISOString(),
    });
  });

  // ---- Remote configuration (the app caches it and works with built-in defaults offline).
  app.get('/api/v1/config', (req, res) => {
    res.json({
      minSupportedVersionCode: 1,
      defaultEmergencyNumber: '112',
      emergencyNumbers: EMERGENCY_NUMBERS,
      announcement,
      incidentRetentionHours: Math.round(store.ttlMs / 3_600_000),
    });
  });

  const incidentLimiter = rateLimit({ windowMs: 60_000, max: 10 });

  // ---- Emergency incident log
  app.post('/api/v1/incidents', incidentLimiter, (req, res) => {
    const { deviceId, triggeredAt, location, contactConfigured, appVersion } = req.body || {};
    if (typeof deviceId !== 'string' || !UUID_RE.test(deviceId)) {
      return res.status(400).json({ error: 'deviceId must be a UUID' });
    }
    if (typeof triggeredAt !== 'number' || !Number.isFinite(triggeredAt) || Math.abs(Date.now() - triggeredAt) > 7 * 86_400_000) {
      return res.status(400).json({ error: 'triggeredAt must be a recent epoch-millisecond timestamp' });
    }
    if (!validLocation(location)) {
      return res.status(400).json({ error: 'location must be {lat, lng, accuracy?} with valid ranges' });
    }
    if (appVersion !== undefined && (typeof appVersion !== 'string' || appVersion.length > 32)) {
      return res.status(400).json({ error: 'appVersion must be a short string' });
    }
    const incident = store.create({
      deviceId,
      triggeredAt,
      location: location ? { lat: location.lat, lng: location.lng, accuracy: location.accuracy ?? null } : null,
      contactConfigured,
      appVersion,
    });
    console.log(`[incident] created ${incident.id} location=${incident.location ? 'yes' : 'no'}`);
    return res.status(201).json(toPublic(incident));
  });

  // Reading or resolving an incident requires the deviceId that created it.
  function ownedIncident(req, res) {
    const incident = store.get(req.params.id);
    const deviceId = req.body?.deviceId || req.query.deviceId;
    if (!incident || incident.deviceId !== deviceId) {
      res.status(404).json({ error: 'Incident not found' });
      return null;
    }
    return incident;
  }

  app.get('/api/v1/incidents/:id', (req, res) => {
    const incident = ownedIncident(req, res);
    if (incident) res.json(toPublic(incident));
  });

  app.post('/api/v1/incidents/:id/resolve', incidentLimiter, (req, res) => {
    const incident = ownedIncident(req, res);
    if (!incident) return;
    store.resolve(incident.id);
    console.log(`[incident] resolved ${incident.id}`);
    res.json(toPublic(incident));
  });

  // ---- Aggregate, non-identifying statistics
  app.get('/api/v1/stats', (req, res) => {
    res.json({ service: SERVICE, incidents: store.stats() });
  });

  app.use((req, res) => res.status(404).json({ error: 'Not found' }));

  // eslint-disable-next-line no-unused-vars
  app.use((err, req, res, next) => {
    if (err.type === 'entity.parse.failed') return res.status(400).json({ error: 'Invalid JSON body' });
    if (err.type === 'entity.too.large') return res.status(413).json({ error: 'Request body too large' });
    console.error(err);
    return res.status(500).json({ error: 'Internal server error' });
  });

  return app;
}

module.exports = { createApp };
