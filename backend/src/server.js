'use strict';

const { createApp } = require('./app');

const port = Number(process.env.PORT) || 3000; // Render injects PORT
const app = createApp();

const server = app.listen(port, () => {
  console.log(`SenseConnect backend listening on port ${port}`);
});

for (const signal of ['SIGTERM', 'SIGINT']) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
