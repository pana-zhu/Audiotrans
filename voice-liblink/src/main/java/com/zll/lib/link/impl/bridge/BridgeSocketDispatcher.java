package com.zll.lib.link.impl.bridge;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.atomic.AtomicBoolean;

import com.zll.lib.link.core.IoArgs;
import com.zll.lib.link.core.ReceiveDispatcher;
import com.zll.lib.link.core.Receiver;
import com.zll.lib.link.core.SendDispatcher;
import com.zll.lib.link.core.SendPacket;
import com.zll.lib.link.core.Sender;
import com.zll.lib.link.utils.plugin.CircularByteBuffer;

/**
 * 桥接调度器实现 当前调度器同时实现了发送者与接受者调度逻辑 目的把接收者收到的数据全部转发给发送者
 * 
 * @author Administrator
 *
 */
public class BridgeSocketDispatcher implements ReceiveDispatcher, SendDispatcher {

	// 数据暂存缓冲区
	private final CircularByteBuffer mBuffer = new CircularByteBuffer(512, true);
	// 根据缓冲区得到的读取 写入通道 ，（ioargs通过通道实现消费的）
	private final ReadableByteChannel readableByteChannel = Channels.newChannel(mBuffer.getInputStream());
	private final WritableByteChannel writableByteChannel = Channels.newChannel(mBuffer.getOutputStream());

	// 接收ioargs，false表示：无数据不强求填满，有多少返回多少,之前limit区间必须填充满
	private final IoArgs receiveIoArgs = new IoArgs(256, false);
	// 发送ioargs
	private IoArgs sendIoArgs = new IoArgs();
	private Receiver receiver;
	private volatile Sender sender;
	// 当前是否处于发送状态
	private AtomicBoolean isSending = new AtomicBoolean();

	public BridgeSocketDispatcher(Receiver receiver) {
		this.receiver = receiver;
	}

	/*
	 * 绑定sender并请求发送数据
	 * 绑定一个新的发送者（将接收到的数据转发给发送者），在绑定时，将老的发送者对应的调度设置为null
	 * 
	 */
	public void bindSender(Sender sender) {
		// 清理老的发送者回调
		final Sender olderSender = this.sender;
		if (olderSender != null) {
			olderSender.setSendListener(null);
		}
		// 清理操作
		synchronized (isSending) {
			isSending.set(false);
		}

		mBuffer.clear();

		// 设置新的发送者
		this.sender = sender;
		if (sender != null) {
			sender.setSendListener(senderEventProcessor);
			requestSend();
		}
	}

	private void requestSend() {
		synchronized (isSending) {
			final Sender sender = this.sender;
			if (isSending.get() || sender == null) {
				return;
			}
			// 返回true代表有数据需要发送
			if (mBuffer.getAvailable() > 0) {
				try {
					boolean isSucceed = sender.postSendAsync();
					if (isSucceed) {
						isSending.set(true);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
	}

	@Override
	public void close() throws IOException {
	}

	@Override
	public void send(SendPacket packet) {
	}

	@Override
	public void cancel(SendPacket packet) {
	}

	@Override
	public void sendHeartBeat() {
	}

	/*
	 * 外部初始化好了桥接调度器后需要调用start方法开始
	 * 
	 */
	@Override
	public void start() {
		// TODO Auto-generated method stub
		receiver.setReceiveListener(receiverEventProcessor);
		registerReceive();
	}

	private void registerReceive() {
		try {
			receiver.postReceiveAsync();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void stop() {
		// TODO Auto-generated method stub

	}

	private final IoArgs.IoArgsEventProcessor receiverEventProcessor = new IoArgs.IoArgsEventProcessor() {

		@Override
		public IoArgs providerIoArgs() {
			receiveIoArgs.resetLimit();
			// 一份新的ioargs需要调用一次开始写入数据的操作
			receiveIoArgs.startWriting();
			return receiveIoArgs;
		}

		@Override
		public void onConsumeFailed(IoArgs args, Exception e) {
			e.printStackTrace();
		}

		@Override
		public void onConsumeCompleted(IoArgs args) {
			args.finishWriting();
			try {
				args.writeTo(writableByteChannel);
			} catch (Exception e) {
				e.printStackTrace();
			}

			registerReceive();
			// 接收数据后请求发送数据
			requestSend();
		}
	};

	private final IoArgs.IoArgsEventProcessor senderEventProcessor = new IoArgs.IoArgsEventProcessor() {

		@Override
		public IoArgs providerIoArgs() {
			try {
				int available = mBuffer.getAvailable();
				IoArgs args = BridgeSocketDispatcher.this.sendIoArgs;
				if (available > 0) {
					args.limit(available);
					args.startWriting();
					args.readFrom(readableByteChannel);
					args.finishWriting();
					return args;
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
			return null;
		}

		@Override
		public void onConsumeFailed(IoArgs args, Exception e) {
			// TODO Auto-generated method stub
			e.printStackTrace();
			// 设置当前发送状态
			synchronized (isSending) {
				isSending.set(false);
			}
			// 继续请求发送当前的数据
			requestSend();
		}

		@Override
		public void onConsumeCompleted(IoArgs args) {
			// 设置当前发送状态
			synchronized (isSending) {
				isSending.set(false);
			}
			requestSend();
		}
	};

}
