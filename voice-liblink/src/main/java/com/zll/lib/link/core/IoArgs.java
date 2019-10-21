package com.zll.lib.link.core;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;

public class IoArgs {

	// 单次操作最大区间
	private volatile int limit;
	// 是否需要消费所有的区间（读取 ，写入）
	private final boolean isNeedConsumeRemaining;
	// buffer
	private final ByteBuffer buffer;

	public IoArgs() {
		this(256);
	}

	public IoArgs(int size) {
		this(size, true);
	}

	public IoArgs(int size, boolean isNeedConsumeRemaining) {
		this.limit = size;
		this.isNeedConsumeRemaining = isNeedConsumeRemaining;
		this.buffer = ByteBuffer.allocate(size);
	}

	/*
	 * 从inputstream流中读取数据
	 */
	public int readFrom(ReadableByteChannel channel) throws IOException {
		// startWriting();
		int bytesProduced = 0;
		while (buffer.hasRemaining()) {
			int len = channel.read(buffer);
			if (len < 0) {
				throw new EOFException();
			}
			bytesProduced += len;
		}
		// finishWriting();
		return bytesProduced;
	}

	/*
	 * 写入数据到inputstream中
	 */
	public int writeTo(WritableByteChannel channel) throws IOException {

		int bytesProduced = 0;
		while (buffer.hasRemaining()) {
			int len = channel.write(buffer);
			if (len < 0) {
				throw new EOFException();
			}
			bytesProduced += len;
		}

		return bytesProduced;
	}

	/*
	 * 从socketchannel读取数据
	 */

	public int readFrom(SocketChannel sc) throws IOException {

		ByteBuffer buffer = this.buffer;
		int bytesProduced = 0;
		int len = 0;
		do {
			// 一次性读满buffer,在发送过程中，channel有可能不可用，需要重新注册，因此此时写的内容可能为0
			len = sc.read(buffer);
			if (len < 0) {
				throw new EOFException("Current read any data with: " + sc);
			}
			bytesProduced += len;
		} while (buffer.hasRemaining() && len != 0);
		return bytesProduced;
	}

	/*
	 * 写数据到socketchannel 一次写完所有数据
	 */

	public int writeTo(SocketChannel sc) throws IOException {

		ByteBuffer buffer = this.buffer;
		int bytesProduced = 0;
		int len;
		do {
			// 一次性写完buffer,在发送过程中，channel有可能不可用，需要重新注册，因此此时写的内容可能为0
			len = sc.write(buffer);
			if (len < 0) {
				throw new EOFException("Current write any data with: " + sc);
			}
			bytesProduced += len;
		} while (buffer.hasRemaining() && len != 0);

		return bytesProduced;
	}

	/*
	 * 开始写入数据到Ioargs
	 */
	public void startWriting() {
		this.buffer.clear();
		// 定义容纳区间
		buffer.limit(limit);
	}

	/*
	 * 写完数据后
	 */
	public void finishWriting() {
		buffer.flip();
	}

	/*
	 * 定义单次写操作的容纳区间
	 */
	public void limit(int limit) {
		this.limit = Math.min(limit, buffer.capacity());
	}

	/*
	 * 重置最大限制
	 */
	public void resetLimit() {
		this.limit = buffer.capacity();
	}

	/*
	 * public void writeLenth(int total) { startWriting(); buffer.putInt(total);
	 * finishWriting(); }
	 */

	public int readLenth() {
		return buffer.getInt();
	}

	/*
	 * 提供者，处理者；数据的生产或消费
	 */
	public interface IoArgsEventProcessor {

		// 提供一份可消费的IoArgs
		IoArgs providerIoArgs();

		// 消费成功
		void onConsumeCompleted(IoArgs args);

		// 消费失败时回调
		void onConsumeFailed(IoArgs args, Exception e);
		
	}

	public int capacities() {
		// TODO Auto-generated method stub
		return buffer.capacity();
	}

	public boolean remained() {
		return buffer.remaining() > 0;
	}

	public boolean isNeedConsumeRemaining() {
		return isNeedConsumeRemaining;
	}

	/*
	 * 从byte数组进行消费
	 */
	public int readFrom(byte[] bytes, int offset, int count) {
		int size = Math.min(count, buffer.remaining());
		if (size <= 0) {
			return 0;
		}
		buffer.put(bytes, offset, size);
		return size;
	}

	/*
	 * 写入数据到byte数组
	 */
	public int writeTo(byte[] bytes, int offset) {
		int size = Math.min(bytes.length - offset, buffer.remaining());
		buffer.get(bytes, offset, size);
		return size;
	}

	/*
	 * 填充数据 size 表示想要填充的数据长度 return z真实填充的数据长度
	 */
	public int fillEmpty(int size) {
		int fillSize = Math.min(size, buffer.remaining());
		// 填充满空数据
		buffer.position(buffer.position() + fillSize);
		return fillSize;
	}

	/*
	 * 清空部分数据 ,不读取这部分数据 size 想要清空的数据长度 return 真实清空的数据长度
	 */
	public int setEmpty(int size) {
		int emptySize = Math.min(size, buffer.remaining());
		buffer.position(buffer.position() + emptySize);
		return emptySize;
	}

}
