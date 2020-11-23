/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.common.network;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ScatteringByteChannel;

/**
 * A size delimited Receive that consists of a 4 byte network-ordered size N followed by N bytes of content
 */
public class NetworkReceive implements Receive {

    public final static String UNKNOWN_SOURCE = "";
    public final static int UNLIMITED = -1;

    private final String source;
    private final ByteBuffer size;
    private final int maxSize;
    private ByteBuffer buffer;


    public NetworkReceive(String source, ByteBuffer buffer) {
        this.source = source;
        this.buffer = buffer;
        this.size = null;
        this.maxSize = UNLIMITED;
    }

    public NetworkReceive(String source) {
        this.source = source;
        this.size = ByteBuffer.allocate(4);
        this.buffer = null;
        this.maxSize = UNLIMITED;
    }

    public NetworkReceive(int maxSize, String source) {
        this.source = source;
        //存储4个字节的数据长度
        this.size = ByteBuffer.allocate(4);
        //数据内容
        this.buffer = null;
        this.maxSize = maxSize;
    }

    public NetworkReceive() {
        this(UNKNOWN_SOURCE);
    }

    @Override
    public String source() {
        return source;
    }

    @Override
    public boolean complete() {
        //size已经读取完毕并且buffer也读取完毕
        return !size.hasRemaining() && !buffer.hasRemaining();
    }

    @Override
    public long readFrom(ScatteringByteChannel channel) throws IOException {
        return readFromReadableChannel(channel);
    }

    // Need a method to read from ReadableByteChannel because BlockingChannel requires read with timeout
    // See: http://stackoverflow.com/questions/2866557/timeout-for-socketchannel-doesnt-work
    // This can go away after we get rid of BlockingChannel
    @Deprecated
    public long readFromReadableChannel(ReadableByteChannel channel) throws IOException {
        int read = 0;
        //说明size还没有读取完成，在拆包中或者第一次读取会命中该条件
        if (size.hasRemaining()) {
            //从channel中读数据读到size中
            int bytesRead = channel.read(size);
            if (bytesRead < 0) {
                throw new EOFException();
            }
            //更新已读字节数
            read += bytesRead;
            //如果size读满了，说明四个字节的数据长度已经读取完毕
            if (!size.hasRemaining()) {
                //将buffer的position重置,以便于下次读取使用
                size.rewind();
                //接收字节数
                int receiveSize = size.getInt();
                if (receiveSize < 0) {
                    throw new InvalidReceiveException("Invalid receive (size = " + receiveSize + ")");
                }
                //大于最大size数或者-1超出了范围
                if (maxSize != UNLIMITED && receiveSize > maxSize) {
                    throw new InvalidReceiveException("Invalid receive (size = " + receiveSize + " larger than " + maxSize + ")");
                }
                //分配一个 size 字节的ByteBuffer，准备接收实际数据
                this.buffer = ByteBuffer.allocate(receiveSize);
            }
        }
        //如果buffer!=null说明已经接收到了size数或者上次未读取完整数据
        if (buffer != null) {
            int bytesRead = channel.read(buffer);
            if (bytesRead < 0) {
                throw new EOFException();
            }
            //更新已读字节数
            read += bytesRead;
        }

        return read;
    }

    public ByteBuffer payload() {
        return this.buffer;
    }

}
