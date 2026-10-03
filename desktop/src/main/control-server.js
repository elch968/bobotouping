'use strict';

const net = require('net');

/**
 * TCP 控制通道，按行分隔 JSON。
 * 手机连上来后第一条消息必须是 hello，且令牌要匹配，否则直接断开。
 */
class ControlServer {
  constructor({ port, token, onHello, onMessage, onDisconnect, log = console }) {
    this.port = port;
    this.token = token;
    this.onHello = onHello;
    this.onMessage = onMessage;
    this.onDisconnect = onDisconnect;
    this.log = log;

    this.clients = new Set();
    this.server = null;
  }

  start() {
    this.server = net.createServer((sock) => this.handle(sock));
    this.server.on('error', (err) => {
      this.log.error('[control] 服务错误:', err.message);
    });
    this.server.listen(this.port, () => {
      this.log.info(`[control] 监听 0.0.0.0:${this.port}`);
    });
  }

  handle(sock) {
    sock.setNoDelay(true);
    sock.setKeepAlive(true, 5000);

    const client = { socket: sock, info: null, authorized: false };
    let buffer = '';

    sock.on('data', (chunk) => {
      buffer += chunk.toString('utf8');

      let index = buffer.indexOf('\n');
      while (index >= 0) {
        const line = buffer.slice(0, index).trim();
        buffer = buffer.slice(index + 1);
        if (line) {
          try {
            this.dispatch(client, JSON.parse(line));
          } catch (_) {
            this.log.warn('[control] 收到无法解析的消息');
          }
        }
        index = buffer.indexOf('\n');
      }

      // 防御异常客户端把缓冲区撑爆
      if (buffer.length > 1 << 20) buffer = '';
    });

    const cleanup = () => {
      if (!this.clients.has(client)) return;
      this.clients.delete(client);
      this.log.info('[control] 手机已断开');
      if (this.onDisconnect) this.onDisconnect(client);
    };

    sock.on('close', cleanup);
    sock.on('error', (err) => {
      this.log.warn('[control] 连接错误:', err.message);
      cleanup();
    });
  }

  dispatch(client, msg) {
    if (msg.t === 'hello') {
      if (this.token && msg.token !== this.token) {
        this.log.warn('[control] 令牌不匹配，拒绝连接');
        client.socket.destroy();
        return;
      }
      client.authorized = true;
      client.info = msg;
      this.clients.add(client);
      this.log.info(`[control] 已连接: ${msg.model || '未知设备'}`);
      if (this.onHello) this.onHello(client, msg);
      return;
    }

    if (!client.authorized) return;

    if (msg.t === 'ping') {
      this.send(client, { t: 'pong', ts: msg.ts });
      return;
    }

    if (this.onMessage) this.onMessage(client, msg);
  }

  send(client, obj) {
    if (!client || !client.socket || client.socket.destroyed) return false;
    try {
      client.socket.write(`${JSON.stringify(obj)}\n`);
      return true;
    } catch (_) {
      return false;
    }
  }

  broadcast(obj) {
    let sent = 0;
    for (const client of this.clients) {
      if (this.send(client, obj)) sent += 1;
    }
    return sent;
  }

  get clientCount() {
    return this.clients.size;
  }

  stop() {
    for (const client of this.clients) {
      try {
        client.socket.destroy();
      } catch (_) {
        /* 忽略 */
      }
    }
    this.clients.clear();
    if (this.server) {
      try {
        this.server.close();
      } catch (_) {
        /* 忽略 */
      }
      this.server = null;
    }
  }
}

module.exports = { ControlServer };
