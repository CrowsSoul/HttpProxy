package proxy;

import java.io.*;
import java.net.*;

public class SimpleProxy implements Proxy,Runnable{
    private final Socket clientSocket;

    public SimpleProxy(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try (BufferedReader clientInput = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter clientOutput = new PrintWriter(clientSocket.getOutputStream(), true)) {

            // 读取客户端请求
            String requestLine = clientInput.readLine();
            if (requestLine != null) {
                String[] requestParts = requestLine.split(" ");
                String method = requestParts[0]; // 请求方法
                String url = requestParts[1]; // 请求 URL
                String version = requestParts[2]; // HTTP 版本

                // 将 URL 转换为 URL 对象
                URL targetUrl = null; // 目标 URL
                try {
                    targetUrl = new URL(url);
                } catch (MalformedURLException e) {
                    return;
                }
                String host = targetUrl.getHost(); // 目标主机
                // 这里限制处理特定域名的请求
                if (!host.equals("httpbin.org") && !host.equals("jsonplaceholder.typicode.com")) {
                    return;
                }
                // 获取目标主机和端口
                int port = targetUrl.getPort() == -1 ? (targetUrl.getProtocol().equals("https") ? 443 : 80) : targetUrl.getPort();
                System.out.println("目标主机: " + host + ":" + port);

                // 连接到目标服务器
                try (Socket serverSocket = new Socket(host, port);
                     PrintWriter serverOutput = new PrintWriter(serverSocket.getOutputStream(), true);
                     BufferedReader serverInput = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()))) {

                    serverSocket.setSoTimeout(5000); // 设置5秒超时
                    // 转发请求到目标服务器
                    System.out.println("----------RequestHeader----------");
                    serverOutput.println(method + " " + url + " " + version);
                    System.out.println(method + " " + url + " " + version);

                    // 读取并转发请求头
                    String headerLine;
                    int bodyLength = -1; // 默认请求体长度为-1
                    while ((headerLine = clientInput.readLine()) != null && !headerLine.isEmpty()) {
                        serverOutput.println(headerLine);
                        System.out.println(headerLine);

                        // 检查是否有 Content-Length 头部
                        if (headerLine.toLowerCase().startsWith("content-length:")) {
                            bodyLength = Integer.parseInt(headerLine.split(": ")[1]);
                        }
                    }
                    serverOutput.println(); // 结束请求头
                    System.out.println("----------End----------");
                    // 发送请求体（如果有的话）
                    if (bodyLength > 0) {
                        System.out.println("请求体长度"+bodyLength);
                        sendRequestBody(clientInput, serverOutput, bodyLength);
                    }

                    // 读取目标服务器响应并转发给客户端
                    String responseLine;
                    int contentLength = -1; // 默认响应体长度为-1
                    System.out.println("----------ResponseHeader----------");
                    // 读取并转发响应头
                    while ((responseLine = serverInput.readLine()) != null) {
                        clientOutput.println(responseLine);
                        System.out.println(responseLine);
                        clientOutput.flush(); // 确保立即发送到客户端
                        // 当读到换行符时，说明响应头已经结束，可以开始读取响应体
                        if (responseLine.isEmpty()) {
                            break;
                        }
                        String[] headerParts = responseLine.split(": ", 2); // 只分割成两个部分
                        if (headerParts[0].equalsIgnoreCase("Content-Length")) {
                            contentLength = Integer.parseInt(headerParts[1]);
                        }
                    }
                    System.out.println("----------End----------");

                    System.out.println("----------ResponseBody----------");
                    // 读取并转发响应体
                    if (contentLength > 0) {
                        int totalBytesRead = 0; // 记录已读取的字节数
                        char[] buffer = new char[4096]; // 用于读取响应体的缓冲区
                        int bytesRead;

                        // 继续读取，直到读取到的字节数大于或等于 Content-Length
                        while (totalBytesRead < contentLength && (bytesRead = serverInput.read(buffer, 0, Math.min(buffer.length, contentLength - totalBytesRead))) != -1) {
                            clientOutput.print(new String(buffer, 0, bytesRead)); // 转发读取的内容
                            clientOutput.flush(); // 确保立即发送到客户端
                            System.out.print(new String(buffer, 0, bytesRead)); // 打印到控制台
                            totalBytesRead += bytesRead; // 更新已读取的字节数
                        }
                        System.out.println("----------End----------");
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // 用于发送请求体的辅助方法
    private void sendRequestBody(BufferedReader clientInput, PrintWriter serverOutput, int bodyLength) throws IOException {
        StringBuilder requestBody = new StringBuilder();
        char[] buffer = new char[4096];
        int totalBytesRead = 0;

        // 读取请求体
        while (totalBytesRead < bodyLength) {
            int bytesRead = clientInput.read(buffer, 0, Math.min(buffer.length, bodyLength - totalBytesRead));
            if (bytesRead == -1) {
                System.out.println("连接关闭，读取请求体时未能获取完整数据。");
                break; // 连接关闭
            }
            requestBody.append(buffer, 0, bytesRead);
            totalBytesRead += bytesRead; // 更新已读取的字节数
            System.out.printf("读取了 %d 字节，总共 %d 字节。%n", bytesRead, totalBytesRead);
        }

        // 转发请求体
        serverOutput.print(requestBody.toString()); // 转发请求体
        System.out.println("----------RequestBody----------");
        System.out.println(requestBody.toString()); // 打印请求体
        serverOutput.println(); // 结束请求体
        System.out.println("----------End----------");
    }

}
