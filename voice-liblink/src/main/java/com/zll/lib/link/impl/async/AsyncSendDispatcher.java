package com.zll.lib.link.impl.async;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import com.zll.lib.link.core.IoArgs;
import com.zll.lib.link.core.IoArgs.IoArgsEventProcessor;
import com.zll.lib.link.core.SendDispatcher;
import com.zll.lib.link.core.SendPacket;
import com.zll.lib.link.core.Sender;
import com.zll.lib.link.utils.CloseUtils;

/**
 * 异步发送数据的调度封装
 * 
 * @author Administrator packet ->（解包成） ioargs ->（将ioargs丢个sender） sender
 */
public class AsyncSendDispatcher implements SendDispatcher, IoArgsEventProcessor, AsyncFragmentReader.PacketProvider {

	private final AtomicBoolean isClosed = new AtomicBoolean(false);
	// 是否处于发送过程中，就是处于从队列中将packet取出，解包成ioargs 再传给sender
	private AtomicBoolean isSending = new AtomicBoolean();

	private final Sender sender;
	private final Queue<SendPacket> queue = new ConcurrentLinkedDeque<>();
	private final AsyncFragmentReader reader = new AsyncFragmentReader(this);

	public AsyncSendDispatcher(Sender sender) {
		this.sender = sender;
		sender.setSendListener(this);
	}

	@Override
	public void send(SendPacket packet) {
		queue.offer(packet);
		requestSend();
	}

	/*
	 * 请求网络进行数据发送
	 */
	private void requestSend() {
		synchronized (isSending) {
			if (isSending.get() || isClosed.get()) {
				return;
			}
			if (reader.requestTakePacket()) {
				try {
					isSending.set(true);
					boolean isSucceed = sender.postSendAsync();
					if (!isSucceed) {
						isSending.set(false);
					}
				} catch (IOException e) {
					closeAndNotify();
				}
			}
		}
	}

	@Override
	public void cancel(SendPacket packet) {
		boolean ret;
		ret = queue.remove(packet);
		if (ret) {
			packet.cancel();
			return;
		}
		reader.cancel(packet);
	}

	private void closeAndNotify() {
		CloseUtils.close(this);
	}

	@Override
	public void close() {
		if (isClosed.compareAndSet(false, true)) {
			// reader的关闭操作
			reader.close();
			// 清空队列
			queue.clear();
			synchronized (isSending) {
				isSending.set(false);
			}
		}
	}

	@Override
	public IoArgs providerIoArgs() {
		// 提供一份数据填充到IoArgs中,数据发送使用Ioargs
		return isClosed.get() ? null : reader.fillData();
	}

	@Override
	public void onConsumeFailed(IoArgs args, Exception e) {
		e.printStackTrace();
		synchronized (isSending) {
			isSending.set(false);
		}
		requestSend();
	}

	@Override
	public void onConsumeCompleted(IoArgs args) {
		synchronized (isSending) {
			isSending.set(false);
		}
		requestSend();
	}

	@Override
	public SendPacket takepacket() {
		SendPacket packet = queue.poll();
		if (packet == null) {
			return null;
		}
		if (packet.isCanceled()) {
			// 已取消 不用发送
			return takepacket();
		}
		return packet;
	}

	@Override
	public void completedPacket(SendPacket packet, boolean succeed) {
		CloseUtils.close(packet);
	}

	/*
	 * 发送心跳帧，将心跳帧放到发送队列进行发送
	 * @see com.zll.lib.link.core.SendDispatcher#sendHeartBeat()
	 */
	@Override
	public void sendHeartBeat() {
		if (queue.size() > 0) {
			return;
		}
		if(reader.requestSendHeartBeatFragment()){
			requestSend();
		}
	}
}
