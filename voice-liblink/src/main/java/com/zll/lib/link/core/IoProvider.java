package com.zll.lib.link.core;

import java.io.Closeable;
import java.nio.channels.SocketChannel;

public interface IoProvider extends Closeable {

	boolean registerInput(SocketChannel sc, HandlerProviderCallback callback);

	boolean registerOutput(SocketChannel sc, HandlerProviderCallback callback);

	void unRegisterInput(SocketChannel sc);
	void unRegisterOutput(SocketChannel sc);
	


	abstract class HandlerProviderCallback implements Runnable {
		
		protected volatile IoArgs attach;
		
		protected abstract void canProviderIo(IoArgs args);
		@Override
		public void run() {
			canProviderIo(attach);
		}
		public void checkAttachNull() {
			if(attach !=null){
				throw new IllegalStateException("args is not empty !");
			}
		}
	}
}
