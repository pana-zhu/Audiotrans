package com.zll.lib.link.impl;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import com.zll.lib.link.core.IoProvider;
import com.zll.lib.link.impl.steal.IoTask;
import com.zll.lib.link.impl.steal.StealingSelectorThread;
import com.zll.lib.link.impl.steal.StealingService;

public class IoStealingSelectorProvider implements IoProvider {

	private final IoStealingThread[] threads;
	private final StealingService stealingService;

	public IoStealingSelectorProvider(int poolsize) throws IOException {
		IoStealingThread[] threads = new IoStealingThread[poolsize];
		for (int i = 0; i < poolsize; i++) {
			Selector selector = Selector.open();
			threads[i] = new IoStealingThread("IoProvider-Thread-" + (i + 1), selector);
		}

		StealingService stealingService = new StealingService(threads, 10);
		
		for (IoStealingThread thread : threads) {
			thread.setStealingService(stealingService);
			thread.start();
		}
		this.threads = threads;
		this.stealingService = stealingService;

	}

	@Override
	public void close() throws IOException {
		stealingService.shutdown();
	}

	@Override
	public boolean registerInput(SocketChannel sc, HandlerProviderCallback callback) {
		StealingSelectorThread thread = stealingService.getNotBusyThread();
		if (thread != null) {
			return thread.register(sc, SelectionKey.OP_READ, callback);
		}
		return false;
	}

	@Override
	public boolean registerOutput(SocketChannel sc, HandlerProviderCallback callback) {
		StealingSelectorThread thread = stealingService.getNotBusyThread();
		if (thread != null) {
			return thread.register(sc, SelectionKey.OP_WRITE, callback);
		}
		return false;

	}

	@Override
	public void unRegisterInput(SocketChannel sc) {
		for (IoStealingThread thread : threads) {

			thread.unregister(sc);
		}
	}

	@Override
	public void unRegisterOutput(SocketChannel sc) {
	}

	static class IoStealingThread extends StealingSelectorThread {
		public IoStealingThread(String name, Selector selector) {
			super(selector);
			setName(name);
		}

		@Override
		protected boolean processTask(IoTask task) {
			task.providerCallback.run();
			return false;
		}
	}

}
