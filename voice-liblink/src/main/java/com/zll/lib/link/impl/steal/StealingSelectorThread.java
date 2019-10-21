package com.zll.lib.link.impl.steal;

import java.io.IOException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import com.zll.lib.link.core.IoProvider;
import com.zll.lib.link.utils.CloseUtils;

/**
 * 可窃取任务的线程
 * 
 * @author Administrator
 *
 */
public abstract class StealingSelectorThread extends Thread {

	// 允许的操作
	private static final int VALID_OPS = SelectionKey.OP_READ | SelectionKey.OP_WRITE;
	private final Selector selector;
	// 是否处于运行中
	private volatile boolean isRunning = true;
	// 已就绪任务队列
	private final LinkedBlockingQueue<IoTask> readyTaskQueue = new LinkedBlockingQueue<>();
	// 待注册的任务队列
	private final LinkedBlockingQueue<IoTask> registerTaskQueue = new LinkedBlockingQueue<>();
	// 单次就绪的任务缓存，随后一次性加入到就绪队列中
	private final List<IoTask> onceReadyTaskCache = new ArrayList<>(200);

	// 任务饱和度
	private final AtomicLong saturatingCapacity = new AtomicLong();

	// 用于线程协同的service
	private volatile StealingService stealingService;

	public StealingSelectorThread(Selector selector) {
		this.selector = selector;
	}

	/**
	 * 将通道注册到当前的selector中
	 * 
	 * @param channel
	 * @param ops
	 * @param callback
	 * @return
	 */
	public boolean register(SocketChannel channel, int ops, IoProvider.HandlerProviderCallback callback) {
		if (channel.isOpen()) {
			// **容易出现内存溢出 
			IoTask ioTask = new IoTask(channel, ops, callback);
			registerTaskQueue.offer(ioTask);
			return true;
		} else {
			return false;
		}
	}

	/**
	 * 取消注册，原理类似于注册操作在队列中添加一份取消注册的任务，并将副本变量清空
	 * 
	 * @param channel
	 */
	public void unregister(SocketChannel channel) {
		SelectionKey selectionKey = channel.keyFor(selector);
		if (selectionKey != null && selectionKey.attachment() != null) {
			// 关闭当前可用attach简单判断是否处于队列中
			selectionKey.attach(null);
			// 添加取消操作
			IoTask ioTask = new IoTask(channel, 0, null);
			registerTaskQueue.offer(ioTask);
		}
	}

	/*
	 * 消费当前已注册到队列中的任务
	 * 
	 * @param registerTaskQueue
	 */
	private void consumeRegisterTodoTasks(final LinkedBlockingQueue<IoTask> registerTaskQueue) {
		final Selector selector = this.selector;
		IoTask registerTask = registerTaskQueue.poll();
		while (registerTask != null) {
			try {
				final SocketChannel channel = registerTask.channel;
				int ops = registerTask.ops;
				if (ops == 0) {
					// cancel
					SelectionKey key = channel.keyFor(selector);
					if (key != null) {
						key.cancel();
					}
					// 有效性判断
				} else if ((ops & ~VALID_OPS) == 0) {
					SelectionKey key = channel.keyFor(selector);
					if (key == null) {
						key = channel.register(selector, ops, new KeyAttachment());
					} else {
						key.interestOps(key.interestOps() | ops);
					}
					Object attachment = key.attachment();
					if (attachment instanceof KeyAttachment) {
						((KeyAttachment) attachment).attach(ops, registerTask);
					} else {
						// 外部关闭 直接取消
						key.cancel();
					}
				}
			} catch (ClosedChannelException | CancelledKeyException | ClosedSelectorException ingored) {
			} finally {
				registerTask = registerTaskQueue.poll();
			}
		}

	}

	/*
	 * 将单次就绪的任务加入到总队列中
	 * 
	 * @param readyTaskQueue
	 * 
	 * @param onceReadyTaskCache
	 */
	private void joinTaskQueue(final LinkedBlockingQueue<IoTask> readyTaskQueue,
			final List<IoTask> onceReadyTaskCache) {
		// 将单次缓存队列加入到总任务队列，可以通过若干算法将多个线程缓存队列排序加入 
		readyTaskQueue.addAll(onceReadyTaskCache);
	}

	/*
	 * 消费待完成的任务
	 * 
	 * @param readyTaskQueue
	 * 
	 * @param registerTaskQueue
	 */
	private void consumeTodoTasks(final LinkedBlockingQueue<IoTask> readyTaskQueue,
			LinkedBlockingQueue<IoTask> registerTaskQueue) {

		// 循环把所有任务做完
		IoTask doTask = readyTaskQueue.poll();

		while (doTask != null) {
			// 增加饱和度
			saturatingCapacity.incrementAndGet();
			// 做任务
			if (processTask(doTask)) { // 返回false 
				// 做完工作后添加待注册队列
				registerTaskQueue.offer(doTask);
			}
			// 做下个任务
			doTask = readyTaskQueue.poll();
		}

		// 窃取其他任务
		final StealingService stealingService = this.stealingService;
		if (stealingService != null) {
			doTask = stealingService.steal(readyTaskQueue);
			while (doTask != null) {
				saturatingCapacity.incrementAndGet();
				if (processTask(doTask)) {
					registerTaskQueue.offer(doTask);
				}
				doTask = stealingService.steal(readyTaskQueue);
			}
		}
	}

	@Override
	public void run() {
		super.run();

		final Selector selector = this.selector;
		final LinkedBlockingQueue<IoTask> readyTaskQueue = this.readyTaskQueue;
		final LinkedBlockingQueue<IoTask> registerTaskQueue = this.registerTaskQueue;
		final List<IoTask> onceReadyTaskCache = this.onceReadyTaskCache;

		try {
			while (isRunning) {
				// 加入待注冊的通道
				consumeRegisterTodoTasks(registerTaskQueue);
				// 检查一次
				if ((selector.selectNow()) == 0) {
					// 抛弃cpu轮转，让cpu做其他事
					Thread.yield();
					continue;
				}
				// 处理已就绪的通道
				Set<SelectionKey> selectedKeys = selector.selectedKeys();
				Iterator<SelectionKey> iterator = selectedKeys.iterator();
				// 迭代就绪任务
				while (iterator.hasNext()) {
					SelectionKey selectionKey = iterator.next();
					Object attachmentObj = selectionKey.attachment();
					// 检查有效性
					if (selectionKey.isValid() && attachmentObj instanceof KeyAttachment) {
						final KeyAttachment attachment = (KeyAttachment) attachmentObj;
						try {
							final int readyOps = selectionKey.readyOps();
							int interestOps = selectionKey.interestOps();

							// 是否可读
							if ((readyOps & selectionKey.OP_READ) != 0) {
								onceReadyTaskCache.add(attachment.taskForReadable);
								// 抹去该事件
								interestOps = interestOps & ~SelectionKey.OP_READ;
							}

							// 是否可写
							if ((readyOps & selectionKey.OP_WRITE) != 0) {
								onceReadyTaskCache.add(attachment.taskForWritable);
								// 抹去该事件
								interestOps = interestOps & ~SelectionKey.OP_WRITE;
							}

							// 取消已就绪的关注
							selectionKey.interestOps(interestOps);
						} catch (CancelledKeyException ingored) {
							// 当前连接被取消 断开时直接移除相关任务
							onceReadyTaskCache.remove(attachment.taskForReadable);
							onceReadyTaskCache.remove(attachment.taskForWritable);
						}
					}
					iterator.remove();
				}

				// 判断本次是否有待执行的任务
				if (!onceReadyTaskCache.isEmpty()) {
					// 加入到总队列
					joinTaskQueue(readyTaskQueue, onceReadyTaskCache);
					// 清空单次缓存队列 
					onceReadyTaskCache.clear();
				}
				// 消费总队列中的任务
				consumeTodoTasks(readyTaskQueue, registerTaskQueue);
			}
		} catch (ClosedSelectorException ignored) {
		} catch (IOException e) {
			CloseUtils.close(selector);
		} finally {
			readyTaskQueue.clear();
			registerTaskQueue.clear();
			onceReadyTaskCache.clear();
		}
	}

	/*
	 * 线程退出操作
	 */
	public void exit() {
		isRunning = false;
		CloseUtils.close(selector);
		// 终止线程执行
		interrupt();
	}

	/*
	 * 获取内部的任务队列
	 * 
	 * @return
	 */
	LinkedBlockingQueue<IoTask> getReadyTaskQueue() {
		return readyTaskQueue;
	}

	public void setStealingService(StealingService stealingService) {
		this.stealingService = stealingService;
	}

	/*
	 * 调用子类执行任务操作
	 * 
	 * @param task
	 * 
	 * @return
	 */
	protected abstract boolean processTask(IoTask task);

	static class KeyAttachment {
		// 可读时执行的任务
		IoTask taskForReadable;
		// 可写时执行的任务
		IoTask taskForWritable;

		/*
		 * 附加任务
		 * 
		 * @param ops
		 * 
		 * @param task
		 */
		void attach(int ops, IoTask task) {
			if (ops == SelectionKey.OP_READ) {
				taskForReadable = task;
			} else {
				taskForWritable = task;
			}
		}
	}

	/**
	 * 获取线程饱和度 暂时的饱和度量时使用任务执行的次数来顶
	 * 
	 * @return
	 */
	long getSaturatinCapacity() {
		if (selector.isOpen()) {
			return saturatingCapacity.get();
		} else {
			return -1;
		}
	}
}
