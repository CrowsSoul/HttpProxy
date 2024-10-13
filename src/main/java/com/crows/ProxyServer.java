package com.crows;

import proxy.AdvancedProxy;
import proxy.CacheProxy;
import proxy.SimpleProxy;

import javax.print.MultiDocPrintService;
import java.io.*;
import java.net.*;
import java.util.Scanner;
import java.util.concurrent.*;

public class ProxyServer {
    private static final int PORT = 10088;
    private static final ExecutorService executor = Executors.newFixedThreadPool(10);

    public static void main(String[] args)
    {
        try (ServerSocket serverSocket = new ServerSocket(PORT); Scanner s = new Scanner(System.in)) {
            System.out.println("请输入用户名");
            // 登录用户名
            String user = s.nextLine().trim();
            System.out.println("Proxy server started on port " + PORT);

            // 无限循环，等待客户端连接
            while (true) {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setSoTimeout(10000); // 设置超时时间为10秒
//                new Thread(new AdvancedProxy(clientSocket, user)).start();
                executor.submit(new AdvancedProxy(clientSocket, user));
            }
        } catch (IOException ignored) {}
    }
}
