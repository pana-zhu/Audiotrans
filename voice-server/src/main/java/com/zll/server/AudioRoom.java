package com.zll.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.zll.foo.handler.ConnectorHandler;

public class AudioRoom {
	private final String roomCode;
	private volatile ConnectorHandler handler1;
	private volatile ConnectorHandler handler2;

	public AudioRoom() {
		this.roomCode = getRandomString(5);
	}

	public String getRoomCode() {
		return roomCode;
	}

	public ConnectorHandler[] getConnectors() {
		List<ConnectorHandler> handlers = new ArrayList<>(2);
		if (handler1 != null) {
			handlers.add(handler1);
		}
		if (handler2 != null) {
			handlers.add(handler2);
		}
		return handlers.toArray(new ConnectorHandler[0]);
	}

	/*
	 * 获取对方
	 */
	public ConnectorHandler getTheOtherHandler(ConnectorHandler handler) {
		return (handler1 == handler || handler1 == null) ? handler2 : handler1;
	}

	/*
	 * 房间是否可聊天，是否两个客户端都具有
	 */
	public synchronized boolean isEnable() {
		return handler1 != null && handler2 != null;
	}

	/*
	 * 加入房间
	 */
	public synchronized boolean enterRoom(ConnectorHandler handler) {
		if (handler1 == null) {
			handler1 = handler;
		} else if (handler2 == null) {
			handler2 = handler;
		} else {
			return false;
		}
		return true;
	}

	/*
	 * 退出房间 return 退出后如果还有一人剩余则返回剩余的人
	 */
	public synchronized ConnectorHandler exitRoom(ConnectorHandler handler) {
		if (handler1 == handler) {
			handler1 = null;
		} else if (handler2 == handler) {
			handler2 = handler;
		}
		return handler1 == null ? handler2 : handler1;
	}

	private String getRandomString(final int len) {
		final String str = "123456789";
		final Random random = new Random();
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < len; ++i) {
			int number = random.nextInt(str.length());
			sb.append(str.charAt(number));
		}
		return sb.toString();
	}

}
