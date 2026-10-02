import express from 'express';
import http from 'http';
import path from 'path';
import { fileURLToPath } from 'url';
import { WebSocketServer, WebSocket } from 'ws';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

interface DeviceRoom {
  streamer: WebSocket | null;
  viewers: Set<WebSocket>;
  lastState: {
    streamState: 'idle' | 'screen' | 'camera_front' | 'camera_back';
    resolution?: string;
    fps?: number;
    updatedAt: number;
  };
  lastTelemetry: {
    battery?: number;
    isCharging?: boolean;
    lat?: number;
    lng?: number;
    accuracy?: number;
    speed?: number;
    updatedAt: number;
  };
  stats: {
    totalFrames: number;
    totalBytes: number;
    startedAt: number;
  };
}

const rooms = new Map<string, DeviceRoom>();

function getOrCreateRoom(deviceId: string): DeviceRoom {
  let room = rooms.get(deviceId);
  if (!room) {
    room = {
      streamer: null,
      viewers: new Set<WebSocket>(),
      lastState: {
        streamState: 'idle',
        updatedAt: Date.now(),
      },
      lastTelemetry: {
        battery: 85,
        isCharging: false,
        lat: 37.7749,
        lng: -122.4194,
        accuracy: 10,
        speed: 0,
        updatedAt: Date.now(),
      },
      stats: {
        totalFrames: 0,
        totalBytes: 0,
        startedAt: Date.now(),
      },
    };
    rooms.set(deviceId, room);
  }
  return room;
}

async function startServer() {
  const app = express();
  const server = http.createServer(app);
  const wss = new WebSocketServer({ noServer: true });

  server.on('upgrade', (request, socket, head) => {
    try {
      const url = new URL(request.url || '', `http://${request.headers.host || 'localhost'}`);
      if (url.pathname === '/ws') {
        wss.handleUpgrade(request, socket, head, (ws) => {
          wss.emit('connection', ws, request);
        });
      }
    } catch {
      socket.destroy();
    }
  });

  app.use(express.json());

  // REST API Endpoints
  app.get('/api/health', (req, res) => {
    res.json({
      status: 'ok',
      service: 'family-safety-websocket-relay',
      activeRooms: rooms.size,
      uptime: process.uptime(),
    });
  });

  app.get('/api/devices', (req, res) => {
    const deviceList = Array.from(rooms.entries()).map(([deviceId, room]) => ({
      deviceId,
      streamerOnline: room.streamer !== null && room.streamer.readyState === WebSocket.OPEN,
      viewerCount: room.viewers.size,
      lastState: room.lastState,
      lastTelemetry: room.lastTelemetry,
      totalFrames: room.stats.totalFrames,
    }));
    res.json({ devices: deviceList });
  });

  app.get('/api/ws-info', (req, res) => {
    const protocol = req.protocol === 'https' ? 'wss' : 'ws';
    const host = req.get('host') || 'localhost:3000';
    res.json({
      wsUrl: `${protocol}://${host}/ws`,
      sampleAndroidUrl: `${protocol}://${host}/ws?deviceId=child_device_001&role=streamer`,
      sampleViewerUrl: `${protocol}://${host}/ws?deviceId=child_device_001&role=viewer`,
    });
  });

  app.use('/web', express.static(path.resolve(__dirname, 'web')));

  // WebSocket Relay Management
  wss.on('connection', (ws: WebSocket, req) => {
    const url = new URL(req.url || '', `http://${req.headers.host || 'localhost'}`);
    let assignedDeviceId = url.searchParams.get('deviceId') || '';
    let assignedRole: 'streamer' | 'viewer' | '' = (url.searchParams.get('role') as any) || '';

    let currentRoom: DeviceRoom | null = null;

    if (assignedDeviceId && assignedRole) {
      currentRoom = getOrCreateRoom(assignedDeviceId);
      if (assignedRole === 'streamer') {
        currentRoom.streamer = ws;
        currentRoom.viewers.forEach((viewer) => {
          if (viewer.readyState === WebSocket.OPEN) {
            viewer.send(
              JSON.stringify({
                type: 'streamer_connected',
                deviceId: assignedDeviceId,
                timestamp: Date.now(),
              })
            );
          }
        });
      } else if (assignedRole === 'viewer') {
        currentRoom.viewers.add(ws);
        ws.send(
          JSON.stringify({
            type: 'init_state',
            deviceId: assignedDeviceId,
            streamerOnline: currentRoom.streamer !== null && currentRoom.streamer.readyState === WebSocket.OPEN,
            lastState: currentRoom.lastState,
            lastTelemetry: currentRoom.lastTelemetry,
          })
        );
      }
    }

    ws.on('message', (data: Buffer | ArrayBuffer | Buffer[], isBinary: boolean) => {
      // Fast relay of raw binary JPEG frames to connected viewers
      if (isBinary) {
        if (currentRoom && currentRoom.streamer === ws) {
          const bufferData = Array.isArray(data) ? Buffer.concat(data) : Buffer.from(data as any);
          currentRoom.stats.totalFrames++;
          currentRoom.stats.totalBytes += bufferData.length;

          currentRoom.viewers.forEach((viewer) => {
            if (viewer.readyState === WebSocket.OPEN) {
              viewer.send(bufferData, { binary: true });
            }
          });
        }
        return;
      }

      // JSON / Text message (commands, registrations, telemetry)
      try {
        const text = data.toString();
        const msg = JSON.parse(text);

        switch (msg.type) {
          case 'register': {
            const deviceId = msg.deviceId || assignedDeviceId || 'child_device_001';
            const role = msg.role || assignedRole || 'viewer';
            assignedDeviceId = deviceId;
            assignedRole = role;
            currentRoom = getOrCreateRoom(deviceId);

            if (role === 'streamer') {
              currentRoom.streamer = ws;
              currentRoom.viewers.forEach((viewer) => {
                if (viewer.readyState === WebSocket.OPEN) {
                  viewer.send(
                    JSON.stringify({
                      type: 'streamer_connected',
                      deviceId,
                      timestamp: Date.now(),
                    })
                  );
                }
              });
              ws.send(JSON.stringify({ type: 'registered', role: 'streamer', deviceId, success: true }));
            } else {
              currentRoom.viewers.add(ws);
              ws.send(
                JSON.stringify({
                  type: 'init_state',
                  deviceId,
                  streamerOnline: currentRoom.streamer !== null && currentRoom.streamer.readyState === WebSocket.OPEN,
                  lastState: currentRoom.lastState,
                  lastTelemetry: currentRoom.lastTelemetry,
                })
              );
            }
            break;
          }

          case 'command': {
            if (currentRoom && currentRoom.streamer && currentRoom.streamer.readyState === WebSocket.OPEN) {
              currentRoom.streamer.send(
                JSON.stringify({
                  type: 'command',
                  action: msg.action,
                  payload: msg.payload || {},
                  timestamp: Date.now(),
                })
              );
              ws.send(
                JSON.stringify({
                  type: 'command_ack',
                  action: msg.action,
                  delivered: true,
                  timestamp: Date.now(),
                })
              );
            } else {
              ws.send(
                JSON.stringify({
                  type: 'command_ack',
                  action: msg.action,
                  delivered: false,
                  error: 'Streamer is currently offline',
                })
              );
            }
            break;
          }

          case 'status': {
            if (currentRoom) {
              currentRoom.lastState = {
                streamState: msg.streamState || 'idle',
                resolution: msg.resolution,
                fps: msg.fps,
                updatedAt: Date.now(),
              };
              currentRoom.viewers.forEach((viewer) => {
                if (viewer.readyState === WebSocket.OPEN) {
                  viewer.send(JSON.stringify(msg));
                }
              });
            }
            break;
          }

          case 'telemetry': {
            if (currentRoom) {
              currentRoom.lastTelemetry = {
                battery: msg.battery ?? currentRoom.lastTelemetry.battery,
                isCharging: msg.isCharging ?? currentRoom.lastTelemetry.isCharging,
                lat: msg.lat ?? currentRoom.lastTelemetry.lat,
                lng: msg.lng ?? currentRoom.lastTelemetry.lng,
                accuracy: msg.accuracy ?? currentRoom.lastTelemetry.accuracy,
                speed: msg.speed ?? currentRoom.lastTelemetry.speed,
                updatedAt: Date.now(),
              };
              currentRoom.viewers.forEach((viewer) => {
                if (viewer.readyState === WebSocket.OPEN) {
                  viewer.send(JSON.stringify(msg));
                }
              });
            }
            break;
          }

          case 'ping': {
            ws.send(JSON.stringify({ type: 'pong', timestamp: msg.timestamp, serverTime: Date.now() }));
            break;
          }

          default:
            break;
        }
      } catch (err) {
        console.error('[WS] Error parsing message:', err);
      }
    });

    ws.on('close', () => {
      if (currentRoom) {
        if (currentRoom.streamer === ws) {
          currentRoom.streamer = null;
          currentRoom.lastState.streamState = 'idle';
          currentRoom.viewers.forEach((viewer) => {
            if (viewer.readyState === WebSocket.OPEN) {
              viewer.send(
                JSON.stringify({
                  type: 'streamer_disconnected',
                  deviceId: assignedDeviceId,
                  timestamp: Date.now(),
                })
              );
            }
          });
        } else {
          currentRoom.viewers.delete(ws);
        }
      }
    });

    ws.on('error', (err) => {
      console.warn('[WS] Socket error:', err.message);
    });
  });

  const isProduction = process.env.NODE_ENV === 'production';
  if (!isProduction) {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    app.use(express.static(path.resolve(__dirname, 'dist')));
    app.get('*', (req, res) => {
      res.sendFile(path.resolve(__dirname, 'dist', 'index.html'));
    });
  }

  const PORT = Number(process.env.PORT) || 3000;
  server.listen(PORT, '0.0.0.0', () => {
    console.log(`🚀 Server listening on http://0.0.0.0:${PORT}`);
    console.log(`⚡ WebSocket relay active at ws://0.0.0.0:${PORT}/ws`);
  });
}

startServer().catch((err) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});