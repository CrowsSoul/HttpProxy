package com.crows;

import proxy.CacheProxy;
import proxy.SimpleProxy;

import java.io.*;
import java.net.*;

public class ProxyServer {
    private static final int PORT = 10088;
    public static void main(String[] args)
    {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Proxy server started on port " + PORT);

            // 无限循环，等待客户端连接
            while (true) {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(10000); // 设置超时时间为10秒
                new Thread(new CacheProxy(clientSocket)).start();
            }
        } catch (IOException ignored) {}
    }
}
