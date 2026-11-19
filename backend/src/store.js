'use strict';

const crypto = require('node:crypto');

/**
 * In-memory SOS incident store with automatic expiry.
 *
 * Privacy by design: incidents (which contain a location) are kept for at most `ttlMs`
 * (24 h by default) and are never written to disk. On Render's free tier the process may
 * restart, which also clears the store - this is documented as a known limitation.
 */
class IncidentStore {
  constructor({ ttlMs = 24 * 60 * 60 * 1000, maxItems = 1000, now = () => Date.now() } = {}) {
    this.ttlMs = ttlMs;
    this.maxItems = maxItems;
    this.now = now;
    this.items = new Map();
    this.totals = { created: 0, resolved: 0 };
  }

  prune() {
    const cutoff = this.now() - this.ttlMs;
    for (const [id, incident] of this.items) {
      if (incident.receivedAtMs < cutoff) this.items.delete(id);
    }
    while (this.items.size > this.maxItems) {
      this.items.delete(this.items.keys().next().value);
    }
  }

  create({ deviceId, triggeredAt, location, contactConfigured, appVersion }) {
    this.prune();
    const id = crypto.randomUUID();
    const receivedAtMs = this.now();
    const incident = {
      id,
      deviceId,
      status: 'active',
      triggeredAt: new Date(triggeredAt).toISOString(),
      receivedAt: new Date(receivedAtMs).toISOString(),
      receivedAtMs,
      location: location || null,
      contactConfigured: Boolean(contactConfigured),
      appVersion: appVersion || null,
      resolvedAt: null,
    };
    this.items.set(id, incident);
    this.totals.created += 1;
    return incident;
  }

  get(id) {
    this.prune();
    return this.items.get(id) || null;
  }

  resolve(id) {
    const incident = this.get(id);
    if (!incident || incident.status === 'resolved') return incident;
    incident.status = 'resolved';
    incident.resolvedAt = new Date(this.now()).toISOString();
    this.totals.resolved += 1;
    return incident;
  }

  stats() {
    this.prune();
    let active = 0;
    for (const incident of this.items.values()) if (incident.status === 'active') active += 1;
    return { retained: this.items.size, active, ...this.totals };
  }
}

/** Public view of an incident: internal fields and the device id are not echoed back. */
function toPublic(incident) {
  const { receivedAtMs, deviceId, ...rest } = incident;
  return rest;
}

module.exports = { IncidentStore, toPublic };
