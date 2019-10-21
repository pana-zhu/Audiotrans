package com.zll.lib.link.core.fragment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;

import com.zll.lib.link.core.Fragment;
import com.zll.lib.link.core.IoArgs;
import com.zll.lib.link.core.SendPacket;

public class SendDirectEntityFragment extends AbsSendPacketFragment {

	private final ReadableByteChannel channel;

	public SendDirectEntityFragment(short identifier, int available, ReadableByteChannel channel, SendPacket packet) {
		super(Math.min(available, Fragment.FRAG_MAX_CAPICITY), Fragment.TYPE_PACKET_ENTITY, Fragment.FLAG_NONE,
				identifier, packet);
		this.channel = channel;
	}

	/**
	 * 通过packet构建内容发送帧 若当前内容无可读内容，则直接发送取消帧
	 * 
	 * @param packet
	 * @param bodyIdentifier
	 * @return
	 */
	static Fragment buildEntityFragment(SendPacket<?> packet, short identifier) {
		int avalialable = packet.available();
		if (avalialable <= 0) {
			// 直流结束
			return new CancelSendFragment(identifier);
		}
		// 构建首帧
		InputStream stream = packet.open();
		ReadableByteChannel channel = Channels.newChannel(stream);
		return new SendDirectEntityFragment(identifier, avalialable, channel, packet);
	}

	@Override
	protected int consumeBody(IoArgs args) throws IOException {
		if (packet == null) {
			// 已终止当前帧，则填充假数据
			return args.fillEmpty(bodyRemaining);
		}
		return args.readFrom(channel);
	}


	@Override
	protected Fragment buildNextFragment() {
		// 直流类型 
		int available = packet.available();
		if(available <= 0 ){
			// 无数据可输出直流结束 
			return new CancelSendFragment(getBodyIdentifier());
		}
		// 下一个帧 
		return new SendDirectEntityFragment(getBodyIdentifier(), available, channel, packet);
	}
}
