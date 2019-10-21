package com.zll.lib.link.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.zll.lib.link.box.BytesReceivePacket;
import com.zll.lib.link.box.FileReceivePacket;
import com.zll.lib.link.box.FileSendPacket;
import com.zll.lib.link.box.StreamDirectReceivePacket;
import com.zll.lib.link.box.StringReceivePacket;
import com.zll.lib.link.box.StringSendPacket;
import com.zll.lib.link.core.ReceiveDispatcher.RecivePacketCallback;
import com.zll.lib.link.impl.SocketChannelAdaptor;
import com.zll.lib.link.impl.async.AsyncReceiveDispatcher;
import com.zll.lib.link.impl.async.AsyncSendDispatcher;
import com.zll.lib.link.impl.bridge.BridgeSocketDispatcher;
import com.zll.lib.link.utils.CloseUtils;

public abstract class Connector implements Closeable, SocketChannelAdaptor.OnChannelStatusChangedListener {

	protected UUID key = UUID.randomUUID();
	private SocketChannel channel;
	private Sender sender;
	private Receiver receiver;
	private SendDispatcher sendDispatcher;
	private ReceiveDispatcher receiveDispatcher;
	private final List<ScheduleJob> scheduleJobs = new ArrayList<>(4);

	public void setup(SocketChannel socketChannel) throws IOException {
		this.channel = socketChannel;
		IoContext context = IoContext.get();

		SocketChannelAdaptor socketChannelAdaptor = new SocketChannelAdaptor(socketChannel, context.getIoProvider(),
				this);
		this.sender = socketChannelAdaptor;
		this.receiver = socketChannelAdaptor;

		sendDispatcher = new AsyncSendDispatcher(sender);
		receiveDispatcher = new AsyncReceiveDispatcher(receiver, receiveCallback);
		// 启动接收
		receiveDispatcher.start();
	}

	public void send(String msg) {
		SendPacket packet = new StringSendPacket(msg);
		// ioargs 与 packet的联系
		sendDispatcher.send(packet);
	}

	public long getLastActiveTime() {
		return Math.max(sender.getLastWriteTime(), receiver.getLastReadTime());
	}

	public void fireIdleTimeoutEvent() {
		sendDispatcher.sendHeartBeat();
	}

	public void schedule(ScheduleJob job) {
		synchronized (scheduleJobs) {
			if (scheduleJobs.contains(job)) {
				return;
			}
			Scheduler scheduler = IoContext.get().getScheduler();
			job.schedule(scheduler);
			scheduleJobs.add(job);
		}
	}

	public void fireExceptionCaught(Throwable throwable) {
		System.out.println("服务端异常！！！");
	}

	// 空闲50s后进行一次检查，之前最后一次时间点

	public void sendFilePacket(SendPacket packet) {
		sendDispatcher.send(packet);
	}

	protected void onRecivePacket(ReceivePacket receivePacket) {
		// System.out.println(key.toString() + ":[New Packet - TYPE]" +
		// receivePacket.type() + ", Length: " + (receivePacket.length/1024) +
		// "KB");
	}

	@Override
	public void onChannelClosed(SocketChannel channel) {
		synchronized (scheduleJobs) {
			for (ScheduleJob scheduleJob : scheduleJobs) {
				scheduleJob.unSchedule();
			}
			scheduleJobs.clear();
		}
		CloseUtils.close(this);
	}

	@Override
	public void close() throws IOException {
		receiveDispatcher.close();
		sendDispatcher.close();
		sender.close();
		receiver.close();
		channel.close();

	}

	// protected abstract File createNewReceiveFile();

	private ReceiveDispatcher.RecivePacketCallback receiveCallback = new RecivePacketCallback() {

		@Override
		public void onReceivePacketCompleted(ReceivePacket packet) {
			onRecivePacket(packet);
		}

		@Override
		public ReceivePacket<?, ?> onArrivedNewPacket(byte type, long length, byte[] headerInfo) {
			switch (type) {
			case Packet.TYPE_MEMORY_BYTES:
				return new BytesReceivePacket(length);
			case Packet.TYPE_MEMORY_STRING:
				return new StringReceivePacket(length);
			case Packet.TYPE_STREAM_FILE:
				return new FileReceivePacket(length, createNewReceiveFile(length, headerInfo));
			case Packet.TYPE_STREAM_DIRECT:
				return new StreamDirectReceivePacket(createNewDirectReceiveOutputStream(length, headerInfo), length);
			default:
				throw new UnsupportedOperationException("Unsupported packet type: " + type);
			}
		}

		@Override
		public void onReceivedHeartBeat() {
			System.out.println(key.toString() + ":[HeartBeat]");
		}
	};

	public UUID getKey() {
		// TODO Auto-generated method stub
		return key;
	}

	protected abstract File createNewReceiveFile(long length, byte[] headerInfo);

	protected abstract OutputStream createNewDirectReceiveOutputStream(long length, byte[] headerInfo);

	/*
	 * 改变当前接收调度器为桥接调度器模式
	 */
	public void changeToBridge() {
		if (receiveDispatcher instanceof BridgeSocketDispatcher) {
			// 已改变 直接返回
			return;
		}
		// 老的停止
		receiveDispatcher.stop();
		// 构建新的接收者调度器
		BridgeSocketDispatcher dispatcher = new BridgeSocketDispatcher(receiver);
		receiveDispatcher = dispatcher;
		// 启动
		dispatcher.start();
	}

	/*
	 * 将另外一个链接的发送者绑定到当前链接的桥接调度器上实现两个链接的桥接功能
	 */
	public void bindToBridge(Sender sender) {
		if (sender == this.sender) {
			throw new UnsupportedOperationException("can not set current connector sender!!");
		}
		if (!(receiveDispatcher instanceof BridgeSocketDispatcher)) {
			throw new IllegalStateException("receiveDispatcher is not BridgeSocketDispatcher!! ");
		}

		((BridgeSocketDispatcher) receiveDispatcher).bindSender(sender);
	}

	/*
	 * 将之前链接的发送者解除绑定，解除桥接数据发送功能
	 */
	public void unBindToBridge() {
		if (!(receiveDispatcher instanceof BridgeSocketDispatcher)) {
			throw new IllegalStateException("receiveDispatcher is not BridgeSocketDispatcher!! ");
		}
		((BridgeSocketDispatcher) receiveDispatcher).bindSender(null);
	}

	/*
	 * 获取当前链接的发送者
	 * 
	 */
	public Sender getSender() {
		return sender;
	}
}
