package me.streamis.socket.io.server;

import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import me.streamis.engine.io.server.EIOSocket;
import me.streamis.engine.io.server.State;
import me.streamis.socket.io.parser.IOParser;
import me.streamis.socket.io.parser.Packet;
import me.streamis.socket.io.parser.Packet.PacketType;

import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

public class Client {

  String id;
  EIOSocket conn;
  MultiMap query;
  private Vertx vertx;
  private SIOServer server;
  private IOParser.Decoder decoder;
  private IOParser.Encoder encoder;
  private Map<String, SIOSocket> sockets;
  private Map<String, SIOSocket> namespaces;
  private Queue<String> connectQueue;
  private Handler<String> onClose = reason -> {
    if (LOGGER.isDebugEnabled()) LOGGER.debug("client close with reason: " + reason);
    SIOSocket sioSocket = sockets.get(id);
    if (sioSocket != null) {
      ((SIOSocketImpl) sioSocket).onClose(reason);
      sockets.remove(id);
      decoder.destroy();
    }
  };

  private static final Logger LOGGER = LoggerFactory.getLogger(Client.class);

  public Client(Vertx vertx, SIOServer sioServer, EIOSocket eioSocket) {
    this.vertx = vertx;
    this.id = eioSocket.getId();
    this.conn = eioSocket;
    this.server = sioServer;
    this.decoder = new IOParser.Decoder();
    this.encoder = new IOParser.Encoder();
    //TODO
    this.sockets = new HashMap<>();
    this.namespaces = new HashMap<>();
    this.connectQueue = new LinkedBlockingQueue<>();
    this.setup();
  }

  private void setup() {
    //TODO onDecoded should be async
    decoder.onDecoded(packet -> {
      if (packet.getType() == PacketType.CONNECT) {
        //TODO
        this.connect(packet.getNamespace(), conn.getTransport().getRequest().params());
      } else {
        //packet from client for namespace.
        SIOSocket socket = this.namespaces.get(packet.getNamespace());
        if (LOGGER.isDebugEnabled()) LOGGER.debug("decode packet from client. " + packet);
        if (socket != null) {
          this.vertx.runOnContext(aVoid -> ((SIOSocketImpl) socket).onPacket(packet));
        } else {
          if (LOGGER.isDebugEnabled()) LOGGER.debug("no socket for namespace " + packet.getNamespace());
        }
      }
    });

    this.conn.messageHandler(data -> {
      if (data instanceof String) {
        this.decoder.add((String) data);
      } else if (data instanceof byte[]) {
        this.decoder.add((byte[]) data);
      } else {
        throw new SocketIOExecption("unKnow data type.");
      }
    });
    this.conn.errorHandler(throwable -> {
      this.sockets.values().forEach(socket -> ((SIOSocketImpl) socket).onError(throwable));
      this.conn.close(true);
    });
    this.conn.closeHandler(onClose);
  }

  /**
   * connect a client to a namespace
   *
   * @param name
   * @param query
   */
  void connect(String name, MultiMap query) {
    if (this.server.namespaces.containsKey(name)) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("connecting to namespace " + name);
      }
      this.query = query;
      doConnect(name, query);
      return;
    }

    this.server.checkNamespace(name, query, dynamicNsp -> {
      if (dynamicNsp != null) {
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug(String.format("dynamic namespace %s was created", dynamicNsp.getName()));
        }
        this.doConnect(name, query);
      } else {
        Packet<String> packet = new Packet<>(PacketType.ERROR, "Invalid namespace");
        packet.setNamespace(name);
        this.packet(packet);
      }
    });
  }

  private void doConnect(String name, MultiMap query) {
    Namespace namespace = this.server.of(name);
    if (!name.equals("/") && !this.namespaces.containsKey(name)) {
      this.connectQueue.offer(name);
      //return;
    }

    ((NamespaceImpl) namespace).add(this, sioSocket -> {
      sockets.put(sioSocket.id(), sioSocket);
      namespaces.put(namespace.getName(), sioSocket);
      if (namespace.getName().equals("/") && connectQueue.size() > 0) {
        String nsp;
        while ((nsp = connectQueue.poll()) != null) connect(nsp, query);
        this.connectQueue.clear();
      }
    });
  }

  void packet(Packet packet) {
    Consumer<Object[]> writeToEngine = encodedPackets -> {
      for (Object encodedPacket : encodedPackets) {
        if (encodedPacket instanceof String) {
          this.conn.send((String) encodedPacket);
        } else if (encodedPacket instanceof Buffer) {
          this.conn.send((Buffer) encodedPacket);
        } else {
          LOGGER.error("unKnow encoded packets." + encodedPacket);
        }
      }
    };

    if (this.conn.getState() == State.OPEN) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER.debug("writing packet " + packet);
      }
      //TODO opts
      encoder.encode(packet, writeToEngine::accept);
    }
  }

  void disconnect() {
    //  this.sockets.values().forEach(socket -> socket.disconnect(true));
    //  this.sockets.clear();
    SIOSocket sioSocket = this.sockets.get(id);
    if (sioSocket != null) {
      sioSocket.disconnect(true);
      this.sockets.remove(id);
      this.close();
    }
  }

  void remove(SIOSocket sioSocket) {
    if (this.sockets.containsKey(sioSocket.id())) {
      String name = sioSocket.namespace().getName();
      this.sockets.remove(sioSocket.id());
      this.namespaces.remove(name);
    }
  }

  private void close() {
    if (this.conn.getState() == State.OPEN) {
      this.conn.close(true);
      this.onClose.handle("forced server close");
    }
  }

}
