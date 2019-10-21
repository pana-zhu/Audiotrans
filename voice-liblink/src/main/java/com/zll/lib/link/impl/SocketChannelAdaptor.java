package com.zll.lib.link.impl;

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import com.zll.lib.link.core.IoArgs;
import com.zll.lib.link.core.IoArgs.IoArgsEventProcessor;
import com.zll.lib.link.core.IoProvider;
import com.zll.lib.link.core.IoProvider.HandlerProviderCallback;
import com.zll.lib.link.core.Receiver;
import com.zll.lib.link.core.Sender;
import com.zll.lib.link.utils.CloseUtils;

public class SocketChannelAdaptor implements Sender, Receiver, Closeable {

	private final AtomicBoolean isClosed = new AtomicBoolean(false);
	private final SocketChannel channel;
	private final IoProvider ioProvider;
	private final OnChannelStatusChangedListener listener;

	private IoArgs.IoArgsEventProcessor receiveIoEventProcessor;
	private IoArgs.IoArgsEventProcessor sendIoEventProcessor;

	private volatile long lastReadTime = System.currentTimeMillis();
	private volatile long lastWriteTime = System.currentTimeMillis();

	public SocketChannelAdaptor(SocketChannel channel, IoProvider ioProvider, OnChannelStatusChangedListener listener)
			throws IOException {
		this.channel = channel;
		this.ioProvider = ioProvider;
		this.listener = listener;

		channel.configureBlocking(false);
	}

	@Override
	public void close() throws IOException {
		// TODO Auto-generated method stub
		if (isClosed.compareAndSet(false, true)) {
			// 解除注册回调
			ioProvider.unRegisterInput(channel);
			ioProvider.unRegisterOutput(channel);
			// 关闭
			CloseUtils.close(channel);
			// 回调当前Channel已关闭
			listener.onChannelClosed(channel);
		}
	}

	public interface OnChannelStatusChangedListener {
		void onChannelClosed(SocketChannel channel);
	}

	@Override
	public boolean postReceiveAsync() throws IOException {
		if (isClosed.get()) {
			throw new IOException("Current channel is closed!");
		}

		// 进行callback状态检查，判断是否属于内部自循环状态
		inputCallback.checkAttachNull();

		return ioProvider.registerInput(channel, inputCallback);
	}

	@Override
	public void setReceiveListener(IoArgsEventProcessor processor) {
		receiveIoEventProcessor = processor;
	}

	@Override
	public boolean postSendAsync() throws IOException {
		if (isClosed.get()) {
			throw new IOException("Current channel is closed!");
		}
		// 进行callback状态检查，判断是否属于内部自循环状态
		outputCallback.checkAttachNull();
		//outputCallback.run();
		//return true;
		return ioProvider.registerOutput(channel, outputCallback);
	}

	@Override
	public void setSendListener(IoArgsEventProcessor processor) {
		sendIoEventProcessor = processor;
	}

	private final IoProvider.HandlerProviderCallback inputCallback = new HandlerProviderCallback() {

		@Override
		protected void canProviderIo(IoArgs args) {
			// TODO Auto-generated method stub
			if (isClosed.get()) {
				return;
			}

			lastReadTime = System.currentTimeMillis();

			// 外层定义接收变量
			IoArgs.IoArgsEventProcessor processor = receiveIoEventProcessor;
			if (processor == null) {
				return;
			}
			if (args == null) {
				args = processor.providerIoArgs();
			}
			try {
				// 具体的读取操作
				if (args == null) {
					processor.onConsumeFailed(null, new IOException("ProviderIoArgs is null."));
				} else {
					int count = args.readFrom(channel);
					/*
					 * if (count == 0) { System.out.println(
					 * "Current read zero data !"); }
					 */
					// 检查是否有空闲区间以及是否需要填满空闲区间
					if (args.remained() && args.isNeedConsumeRemaining()) {
						attach = args;
						// 再次注册数据发送
						ioProvider.registerInput(channel, this);
					} else {
						attach = null;
						// 写入完成回调
						processor.onConsumeCompleted(args);
					}
				}
			} catch (IOException e) {
				CloseUtils.close(SocketChannelAdaptor.this);
			}
		}
	};
	private final IoProvider.HandlerProviderCallback outputCallback = new HandlerProviderCallback() {

		@Override
		protected void canProviderIo(IoArgs args) {
			if (isClosed.get()) {
				return;
			}

			lastWriteTime = System.currentTimeMillis();

			IoArgs.IoArgsEventProcessor processor = sendIoEventProcessor;
			if (processor == null) {
				return;
			}
			if (args == null) {
				args = processor.providerIoArgs();
			}
			try {
				// 具体的写入操作
				if (args == null) {
					processor.onConsumeFailed(null, new IOException("ProviderIoArgs is null."));
				} else {
					int count = args.writeTo(channel);
					if (count == 0) {
						System.out.println("Current write zero data !");
					}
					// 检查是否有空闲区间以及是否需要填满空闲区间
					if (args.remained() && args.isNeedConsumeRemaining()) {
						attach = args;
						// 再次注册数据发送
						ioProvider.registerOutput(channel, this);
					} else {
						attach = null;
						// 写入完成回调
						processor.onConsumeCompleted(args);
					}
				}
			} catch (Exception e) {
				CloseUtils.close(SocketChannelAdaptor.this);
			}
		}
	};

	@Override
	public long getLastReadTime() {
		return lastReadTime;
	}

	@Override
	public long getLastWriteTime() {
		return lastWriteTime;
	}

}
