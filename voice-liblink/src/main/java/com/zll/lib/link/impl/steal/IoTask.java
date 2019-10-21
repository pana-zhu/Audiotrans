package com.zll.lib.link.impl.steal;

import java.nio.channels.SocketChannel;

import com.zll.lib.link.core.IoProvider;

/**
 * 可用以进行调度的任务封装 任务执行的回调、当前任务类型、任务对应的通道
 * 
 * @author Administrator
 */
public class IoTask {
	public final SocketChannel channel;
	public final IoProvider.HandlerProviderCallback providerCallback;
	public final int ops;

	public IoTask(SocketChannel channel, int ops, IoProvider.HandlerProviderCallback providerCallback) {

		this.channel = channel;
		this.providerCallback = providerCallback;
		this.ops = ops;
	}
}
