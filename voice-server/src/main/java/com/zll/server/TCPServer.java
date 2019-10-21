package com.zll.server;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.zll.foo.Command;
import com.zll.foo.handler.ConnectorHandler;
import com.zll.foo.handler.ConnctorStringPacketChain;
import com.zll.foo.handler.ConnectorCloseChain;
import com.zll.lib.link.box.StringReceivePacket;
import com.zll.lib.link.core.Connector;
import com.zll.lib.link.core.ScheduleJob;
import com.zll.lib.link.core.schedule.IdleTimeoutSchedule;
import com.zll.lib.link.utils.CloseUtils;
import com.zll.server.ServerAccepter.AccepterListener;

public class TCPServer implements ServerAccepter.AccepterListener, Group.GroupMessageAdapter {

	private final int port;
	private ServerAccepter serverAccepter;
	private final List<ConnectorHandler> clientHandlerList = new ArrayList<>();
	private final File cachePath;
	private ServerSocketChannel server;

	private final Map<String, Group> groups = new HashMap<String, Group>();

	private final ServerStatistics serverStatistics = new ServerStatistics();

	public TCPServer(int PORT_SERVER, File cachePath) {
		this.port = PORT_SERVER;
		this.cachePath = cachePath;
		this.groups.put(Command.TRANS_COMMAND_GROUP_NAME, new Group(Command.TRANS_COMMAND_GROUP_NAME, this));
	}

	public boolean start() {
		try {
			// selector = Selector.open();
			ServerAccepter serverAccepter = new ServerAccepter(this);
			server = ServerSocketChannel.open();
			// 设置为非阻塞
			server.configureBlocking(false);
			// 绑定本地端口
			server.socket().bind(new InetSocketAddress(port));
			// 注册客户端连接到达监听
			server.register(serverAccepter.getSelector(), SelectionKey.OP_ACCEPT);
			this.server = server;
			this.serverAccepter = serverAccepter;
			serverAccepter.start();

			if (serverAccepter.awaitRunning()) {
				System.out.println("服务器准备就绪~");
				System.out.println("服务器信息： " + server.getLocalAddress().toString());
				return true;
			} else {
				System.out.println("启动异常！");
				return false;
			}
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
	}

	void broadcast(String mg) {
		mg = "系统通知：" + mg;

		ConnectorHandler[] clientHandlers;
		synchronized (clientHandlerList) {
			clientHandlers = clientHandlerList.toArray(new ConnectorHandler[0]);
		}

		for (ConnectorHandler clientHandler : clientHandlers) {
			sendMessageToClient(clientHandler, mg);
		}

	}

	@Override
	public void sendMessageToClient(ConnectorHandler handler, String msg) {
		handler.send(msg);
		serverStatistics.sendSize++;
		System.out.println("服务端发送stringPacket数量： " + serverStatistics.sendSize);
	}

	public void stop() {
		if (serverAccepter != null) {
			serverAccepter.exit();
		}

		ConnectorHandler[] clientHandlers;
		synchronized (clientHandlerList) {
			clientHandlers = clientHandlerList.toArray(new ConnectorHandler[0]);
			clientHandlerList.clear();
		}

		for (ConnectorHandler clientHandler : clientHandlers) {
			clientHandler.exit();
		}

		clientHandlerList.clear();

		CloseUtils.close(server);
	}

	/**
	 * 获取当前的状态信息
	 */
	Object[] getStatusString() {
		return new String[] { "客户端数量： " + clientHandlerList.size(), "发送数量： " + serverStatistics.sendSize,
				"接收数量： " + serverStatistics.recSize };
	}

	@Override
	public void onNewSocketArrived(SocketChannel channel) {
		try {
			ConnectorHandler connectorHandler = new ConnectorHandler(channel, cachePath);
			System.out.println(connectorHandler.getInfo() + ": Connected !");

			// 添加收到消息的处理责任链
			connectorHandler.getStringPacketChain().appendLast(serverStatistics.statisticsChain())
					.appendLast(new ParseCommandConnectorStringPacketChain())
					.appendLast(new ParseAudioStreamCommandStringPacketChain());
			// 添加关闭链接时的处理责任链
			connectorHandler.getCloseChain().appendLast(new RemoveAudioQueueOnConnectorCloseChain())
					.appendLast(new RemoveQueueOnConnectorCloseChain());

			// 空闲调度任务
			ScheduleJob scheduleJob = new IdleTimeoutSchedule(100, TimeUnit.SECONDS, connectorHandler);
			connectorHandler.schedule(scheduleJob);

			synchronized (clientHandlerList) {
				clientHandlerList.add(connectorHandler);
				System.out.println("当前客户端数量： " + clientHandlerList.size());
			}

			// 回送客户端在服务器的唯一标志
			sendMessageToClient(connectorHandler, Command.CMMAND_INFO_NAME + connectorHandler.getKey().toString());
		} catch (IOException e) {
			e.printStackTrace();
			System.out.println("客户端连接异常： " + e.getMessage());
		}
	}

	private class RemoveQueueOnConnectorCloseChain extends ConnectorCloseChain {
		@Override
		protected boolean consume(ConnectorHandler clientHandler, Connector connector) {
			synchronized (clientHandlerList) {
				clientHandlerList.remove(clientHandler);

				// 移除群聊的客户端
				Group group = groups.get(Command.TRANS_COMMAND_GROUP_NAME);
				group.removeMember(clientHandler);
			}
			return true;
		}

	}

	private class ParseCommandConnectorStringPacketChain extends ConnctorStringPacketChain {

		@Override
		protected boolean consume(ConnectorHandler clientHandler, StringReceivePacket packet) {
			String str = packet.entity();

			if (str.startsWith(Command.TRANS_COMMAND_JOIN_GROUP)) {
				// 加入群
				Group group = groups.get(Command.TRANS_COMMAND_GROUP_NAME);
				if (group.addMember(clientHandler)) {
					sendMessageToClient(clientHandler, "Join Group:" + group.getName());
				}
				return true;
			} else if (str.startsWith(Command.TRANS_COMMAND_LEAVE_GROUP)) {
				// 离开群
				Group group = groups.get(Command.TRANS_COMMAND_GROUP_NAME);
				if (group.removeMember(clientHandler)) {
					sendMessageToClient(clientHandler, "Leave Group:" + group.getName());
				}
				return true;
			}
			return false;
		}

		@Override
		protected boolean consumeAgain(ConnectorHandler clientHandler, StringReceivePacket packet) {
			// 捡漏的模式，当我们第一遍未消费，然后又没有加入到群，自然没有后续的节点消费
			// 此时我们进行二次消费，返回发送过来的消息
			sendMessageToClient(clientHandler, packet.entity());
			return true;
		}

	}

	// 房间映射表，房间号-房间的映射
	private final HashMap<String, AudioRoom> audioRoomMap = new HashMap<>(50);
	// 链接与房间的映射表，音频链接-房间映射
	private final HashMap<ConnectorHandler, AudioRoom> audioStreamRootMap = new HashMap<>(100);

	// 音频命令控制与数据流传输链接映射表
	private final HashMap<ConnectorHandler, ConnectorHandler> audioCmdToStreamMap = new HashMap<>(100);
	private final HashMap<ConnectorHandler, ConnectorHandler> audioStreamToCmdMap = new HashMap<>(100);

	/*
	 * 创建房间,生成一个当前缓存列表中没有的房间
	 * 
	 */
	
	private AudioRoom createNewRoom() {
		AudioRoom room;
		do {
			room = new AudioRoom();
		} while (audioRoomMap.containsKey(room.getRoomCode()));
		// 添加到缓存列表
		audioRoomMap.put(room.getRoomCode(), room);
		return room;
	}

	/*
	 * 加入房间
	 */
	private boolean joinRoom(AudioRoom room, ConnectorHandler audioStreamConnector) {

		if (room.enterRoom(audioStreamConnector)) {
			audioStreamRootMap.put(audioStreamConnector, room);
			return true;
		}
		return false;
	}

	/*
	 * 解散房间
	 */
	private void dissolveRoom(ConnectorHandler audioStreamConnector) {
		AudioRoom room = audioStreamRootMap.get(audioStreamConnector);
		if (room == null) {
			return;
		}
		ConnectorHandler[] connectors = room.getConnectors();
		for (ConnectorHandler connectorHandler : connectors) {
			// 解除桥接
			connectorHandler.unBindToBridge();
			// 移除缓存
			audioStreamRootMap.remove(connectorHandler);
			if (connectorHandler != audioStreamConnector) {
				// 退出房间 并获取对方，向对方发送停止
				sendStreamConnectorMessage(connectorHandler, Command.COMMAND_INFO_AUDIO_STOP);
			}
		}

		// 销毁房间
		audioRoomMap.remove(room.getRoomCode());
	}

	/*
	 * 给链接流对应的命令控制链接发送信息
	 */
	private void sendStreamConnectorMessage(ConnectorHandler connectorHandler, String commandInfoAudioStop) {

		if (connectorHandler != null) {
			ConnectorHandler audioCmdConnector = findAudioCmdConnector(connectorHandler);
			sendMessageToClient(audioCmdConnector, commandInfoAudioStop);
		}

	}

	/*
	 * 通过数据传输流链接寻找音频命令控制链接
	 */
	private ConnectorHandler findAudioCmdConnector(ConnectorHandler handler) {
		ConnectorHandler connectorHandler = audioStreamToCmdMap.get(handler);
		if (connectorHandler == null) {
			sendMessageToClient(handler, Command.COMMAND_INFO_AUDIO_ERROR);
			return null;
		}
		return connectorHandler;
	}

	/*
	 * 通过音频命令控制连接寻找数据传输流链接，未找到则发送错误
	 */
	private ConnectorHandler findAudioStreamConnector(ConnectorHandler handler) {
		ConnectorHandler connectorHandler = audioCmdToStreamMap.get(handler);
		if (connectorHandler == null) {
			sendMessageToClient(handler, Command.COMMAND_INFO_AUDIO_ERROR);
			return null;
		}
		return connectorHandler;
	}

	private ConnectorHandler findConnectorFromKey(String key) {
		synchronized (clientHandlerList) {
			for (ConnectorHandler connectorHandler : clientHandlerList) {
				if (connectorHandler.getKey().toString().equalsIgnoreCase(key)) {
					return connectorHandler;
				}
			}
		}
		return null;
	}

	private class ParseAudioStreamCommandStringPacketChain extends ConnctorStringPacketChain {
		/*
		 * ConnectorHandler handler 命令连接
		 */
		@Override
		protected boolean consume(ConnectorHandler handler, StringReceivePacket stringReceivePacket) {

			String str = stringReceivePacket.entity();
			if (str.startsWith(Command.COMMAND_CONNECTOR_BIND)) {
				// 绑定命令，也就是将音频绑定到当前的命令流上
				String key = str.substring(Command.COMMAND_CONNECTOR_BIND.length());
				// 查找命令key对应的流的连接 
				ConnectorHandler audioStreamConnector = findConnectorFromKey(key);
				if (audioStreamConnector != null) {
					// 添加绑定关系,命令链接 流链接相互绑定
					audioCmdToStreamMap.put(handler, audioStreamConnector);
					audioStreamToCmdMap.put(audioStreamConnector, handler);

					// 将查找出来的流链接转换为桥接模式
					audioStreamConnector.changeToBridge();
				}
			} else if (str.startsWith(Command.COMMAND_AUDIO_CREATE_ROOM)) {
				// 创建房间操作
				ConnectorHandler audioStreamConnector = findAudioStreamConnector(handler);
				if (audioStreamConnector != null) {
					// 随机创建房间
					AudioRoom room = createNewRoom();
					// 加入一个客户端
					joinRoom(room, audioStreamConnector);
					// 发送成功消息
					sendMessageToClient(handler, Command.COMMAND_INFO_AUDIO_ROOM + room.getRoomCode());
				}
			} else if (str.startsWith(Command.COMMAND_AUDIO_LEAVE_ROOM)) {
				// 离开房间命令
				ConnectorHandler audioStreamConnector = findAudioStreamConnector(handler);
				if (audioStreamConnector != null) {
					// 任意一人离开都销毁房间
					dissolveRoom(audioStreamConnector);
					// 发送离开消息
					sendMessageToClient(handler, Command.COMMAND_INFO_AUDIO_STOP);
				}
			} else if (str.startsWith(Command.COMMAND_AUDIO_JOIN_ROOM)) {
				// 加入房间操作
				ConnectorHandler audioStreamConnector = findAudioStreamConnector(handler);
				if (audioStreamConnector != null) {
					// 取得房间号
					String roomCode = str.substring(Command.COMMAND_AUDIO_JOIN_ROOM.length());
					AudioRoom room = audioRoomMap.get(roomCode);
					// 如果找到了房间就走后面流程
					if (room != null && joinRoom(room, audioStreamConnector)) {
						// 对方
						ConnectorHandler theOtherHandler = room.getTheOtherHandler(audioStreamConnector);

						// 相互搭建好桥接
						theOtherHandler.bindToBridge(audioStreamConnector.getSender());
						audioStreamConnector.bindToBridge(theOtherHandler.getSender());

						// 给自己命令连接发送成功加入房间消息
						sendMessageToClient(handler, Command.COMMAND_INFO_AUDIO_START);
						// 给对方命令连接发送开始聊天的消息
						sendStreamConnectorMessage(theOtherHandler, Command.COMMAND_INFO_AUDIO_START);
					} else {
						// 房间没找到，房间人员已满
						sendMessageToClient(handler, Command.COMMAND_INFO_AUDIO_ERROR);
					}
				}
			} else {
				return false;
			}

			return true;
		}
	}

	private class RemoveAudioQueueOnConnectorCloseChain extends ConnectorCloseChain {

		@Override
		protected boolean consume(ConnectorHandler clientHandler, Connector connector) {
			if (audioCmdToStreamMap.containsKey(clientHandler)) {
				// 命令链接断开
				audioCmdToStreamMap.remove(connector);
			} else if (audioStreamToCmdMap.containsKey(clientHandler)) {
				// 流断开
				audioStreamToCmdMap.remove(clientHandler);
				// 解散房间
				dissolveRoom(clientHandler); 
			}
			return false;
		}

	}

}
