/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.common.network;


import java.io.IOException;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;

import java.security.Principal;

import org.apache.kafka.common.utils.Utils;

public class KafkaChannel {
    private final String id;
    private final TransportLayer transportLayer;
    private final Authenticator authenticator;
    private final int maxReceiveSize;
    private NetworkReceive receive;
    private Send send;

    public KafkaChannel(String id, TransportLayer transportLayer, Authenticator authenticator, int maxReceiveSize) throws IOException {
        this.id = id;
        this.transportLayer = transportLayer;
        this.authenticator = authenticator;
        this.maxReceiveSize = maxReceiveSize;
    }

    public void close() throws IOException {
        Utils.closeAll(transportLayer, authenticator);
    }

    /**
     * Returns the principal returned by `authenticator.principal()`.
     */
    public Principal principal() throws IOException {
        return authenticator.principal();
    }

    /**
     * Does handshake of transportLayer and authentication using configured authenticator
     */
    public void prepare() throws IOException {
        if (!transportLayer.ready()) {
            transportLayer.handshake();
        }
        if (transportLayer.ready() && !authenticator.complete()) {
            authenticator.authenticate();
        }
    }

    public void disconnect() {
        transportLayer.disconnect();
    }


    public boolean finishConnect() throws IOException {
        return transportLayer.finishConnect();
    }

    public boolean isConnected() {
        return transportLayer.isConnected();
    }

    public String id() {
        return id;
    }

    public void mute() {
        //取消关注OP_READ事件
        transportLayer.removeInterestOps(SelectionKey.OP_READ);
    }

    public void unmute() {
        //添加OP_READ
        transportLayer.addInterestOps(SelectionKey.OP_READ);
    }

    public boolean isMute() {
        return transportLayer.isMute();
    }

    public boolean ready() {
        //PlaintextTransportLayer 默认为true DefaultAuthenticator  默认为true
        return transportLayer.ready() && authenticator.complete();
    }

    public boolean hasSend() {
        return send != null;
    }

    /**
     * Returns the address to which this channel's socket is connected or `null` if the socket has never been connected.
     *
     * If the socket was connected prior to being closed, then this method will continue to return the
     * connected address after the socket is closed.
     */
    public InetAddress socketAddress() {
        return transportLayer.socketChannel().socket().getInetAddress();
    }

    public String socketDescription() {
        Socket socket = transportLayer.socketChannel().socket();
        if (socket.getInetAddress() == null) {
            return socket.getLocalAddress().toString();
        }
        return socket.getInetAddress().toString();
    }

    public void setSend(Send send) {
        //如果上一个正在发送，抛出异常
        if (this.send != null) {
            throw new IllegalStateException("Attempt to begin a send operation with prior send operation still in progress.");
        }
        this.send = send;
        //添加感兴趣的事件：OP_WRITE,说明此Channel进入可写状态
        this.transportLayer.addInterestOps(SelectionKey.OP_WRITE);
    }

    public NetworkReceive read() throws IOException {
        NetworkReceive result = null;

        if (receive == null) {
            receive = new NetworkReceive(maxReceiveSize, id);
        }

        receive(receive);
        //如果已经读取完毕，将receive赋为null，如果没有传输完成，则等待下一次继续读取数据
        if (receive.complete()) {
            receive.payload().rewind();
            result = receive;
            receive = null;
        }
        //只有读取完毕的时候，result不为null
        return result;
    }

    public Send write() throws IOException {
        Send result = null;
        if (send != null && send(send)) {
            result = send;
            send = null;
        }
        return result;
    }

    private long receive(NetworkReceive receive) throws IOException {
        return receive.readFrom(transportLayer);
    }

    /**
     * 发送数据，底层是SocketChannel.write方法
     */
    private boolean send(Send send) throws IOException {
        //写入到transportLayer
        send.writeTo(transportLayer);
        //如果发送完成，则移除OP_WRITE Key
        if (send.completed()) {
            transportLayer.removeInterestOps(SelectionKey.OP_WRITE);
        }
        return send.completed();
    }

}
