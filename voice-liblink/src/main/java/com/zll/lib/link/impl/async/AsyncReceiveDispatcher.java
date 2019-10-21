package com.zll.lib.link.impl.async;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import com.zll.lib.link.core.IoArgs;
import com.zll.lib.link.core.IoArgs.IoArgsEventProcessor;
import com.zll.lib.link.core.ReceiveDispatcher;
import com.zll.lib.link.core.ReceivePacket;
import com.zll.lib.link.core.Receiver;
import com.zll.lib.link.utils.CloseUtils;

/**
 * 数据接收成数据分片，然后再转换为packet 包括reciever的注册和对数据的调度
 * 
 * @author Administrator
 *
 */
public class AsyncReceiveDispatcher
		implements ReceiveDispatcher, IoArgsEventProcessor, AsyncFragmentWriter.PacketProvider {

	private final AtomicBoolean isClosed = new AtomicBoolean(false);
	private final Receiver receiver;
	private final RecivePacketCallback callback;
	private final AsyncFragmentWriter writer = new AsyncFragmentWriter(this);

	public AsyncReceiveDispatcher(Receiver receiver, RecivePacketCallback callback) {
		this.receiver = receiver;
		this.receiver.setReceiveListener(this);
		this.callback = callback;
	}

	@Override
	public void start() {
		// TODO Auto-generated method stub
		registerReceive();
	}

	private void registerReceive() {
		// TODO Auto-generated method stub
		try {
			receiver.postReceiveAsync();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			closeAndNotify();
		}
	}

	private void closeAndNotify() {
		// TODO Auto-generated method stub
		CloseUtils.close(this);
	}

	@Override
	public void stop() {
		// TODO Auto-generated method stub
	}

	@Override
	public void close() throws IOException {
		if (isClosed.compareAndSet(false, true)) {
			writer.close();
		}
	}

	@Override
	public IoArgs providerIoArgs() {
		IoArgs args = writer.takeIoArgs();
		// 一份新的ioargs需要调用一次开始写入数据的操作 
		args.startWriting();
		return args;
	}

	@Override
	public void onConsumeFailed(IoArgs args, Exception e) {
		e.printStackTrace();
	}

	@Override
	public void onConsumeCompleted(IoArgs args) {
		if (isClosed.get()) {
			return;
		}
		// 在消费ioargs数据之前标识数据已经填充完成 
		args.finishWriting();
		do {
			writer.consumeIoArgs(args);
		} while (args.remained() && !isClosed.get());
		registerReceive();
	}

	@Override
	public ReceivePacket takePacket(byte type, long length, byte[] headerInfo) {
		// TODO Auto-generated method stub
		return callback.onArrivedNewPacket(type, length,headerInfo);
	}

	@Override
	public void completedPacket(ReceivePacket packet, boolean isSucceed) {
		// TODO Auto-generated method stub
		CloseUtils.close(packet);
		callback.onReceivePacketCompleted(packet);
	}

	@Override
	public void onReceivedHeartBeatFragment() {
		// TODO Auto-generated method stub
		callback.onReceivedHeartBeat();
	}
}
