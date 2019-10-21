package com.zll.foo;

public class Command {

	// 绑定stream 到一个命令链接（带参数）,语音传输连接与命令连接绑定 
	public static final String COMMAND_CONNECTOR_BIND = "--m c bind ";
	// 创建对话房间
	public static final String COMMAND_AUDIO_CREATE_ROOM = "--m a create";
	// 加入对话房间 （带参数）
	public static final String COMMAND_AUDIO_JOIN_ROOM = "--m a join ";
	// 主动离开对话房间
	public static final String COMMAND_AUDIO_LEAVE_ROOM = "--m a leave";

	// 服务端端向客户端回送服务器上的唯一标志
	public static final String CMMAND_INFO_NAME = "--i server ";
	// 服务端端向客户端回送语音群名（带参数）
	public static final String COMMAND_INFO_AUDIO_ROOM = "--i a room ";
	// 服务端端向客户端回送语音开始（带参数）
	public static final String COMMAND_INFO_AUDIO_START = "--i a start ";
	// 服务端端向客户端回送语音结束
	public static final String COMMAND_INFO_AUDIO_STOP = "--i a stop";
	// 服务端端向客户端回送语音操作错误
	public static final String COMMAND_INFO_AUDIO_ERROR = "--i a error";

	public static final String TRANS_EXIT_COMMAND = "00byebye00";

	public static final String TRANS_COMMAND_JOIN_GROUP = "--m g join";

	public static final String TRANS_COMMAND_LEAVE_GROUP = "--m g leave";

	public static final String TRANS_COMMAND_GROUP_NAME = "PANA";

}
