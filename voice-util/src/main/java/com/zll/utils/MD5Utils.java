package com.zll.utils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import org.apache.commons.codec.digest.DigestUtils;

public class MD5Utils {
	public static void main(String[] args) throws IOException {
		
		String code_src = getCode("E:\\land&sea\\sea.shp");
		String code_dest = getCode("E:\\codespace\\space\\socket_space\\filestrans\\trans-server\\cache\\server\\85e00890-791e-44a0-a323-1389a92abc45.tmp");
		System.out.println(code_src.equals(code_dest));
	}

	static String getCode(String path) throws IOException{
		
		File file = new File(path);
		FileInputStream in = new FileInputStream(file);
		String code = DigestUtils.md5Hex(in);
		return code;
	}
}
