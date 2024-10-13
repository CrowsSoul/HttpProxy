package proxy;

import java.io.*;
import java.net.*;

/**
 * HTTP代理服务器的简单实现
 * 支持字符串类型的请求/响应体
 * 支持GET/POST请求
 * 支持HTTP/1.0/1.1版本
 */
public class SimpleProxy implements Proxy,Runnable{
    protected final Socket clientSocket;
    protected String method;
    protected String url;
    protected String host;
    protected int port;
    protected String version;
    protected PrintWriter serverOutput = null;
    protected PrintWriter clientOutput = null;
    protected BufferedReader serverInput = null;
    protected BufferedReader clientInput = null;

    public SimpleProxy(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try (BufferedReader clientInput = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter clientOutput = new PrintWriter(clientSocket.getOutputStream(), true))
        {
            this.clientInput = clientInput;
            this.clientOutput = clientOutput;
            // 读取服务端信息
            getServerInfo();
            if(method == null || (!method.equals("GET") && !method.equals("POST")))
            {
                return;
            }
            if(host == null ||port!=80)
            {
                return;
            }
            // 连接到目标服务器
            System.out.println("=====连接到目标服务器: " + host + ":" + port + " =====");
            try (Socket serverSocket = new Socket(host, port);
                 PrintWriter serverOutput = new PrintWriter(serverSocket.getOutputStream(), true);
                 BufferedReader serverInput = new BufferedReader(new InputStreamReader(serverSocket.getInputStream()))) {

                this.serverOutput = serverOutput;
                this.serverInput = serverInput;
                serverSocket.setSoTimeout(5000); // 设置5秒超时

                // 发送请求头
                int requestBodyLength = sendRequestHeader();

                // 发送请求体（如果有的话）
                if (requestBodyLength > 0) {
                    sendRequestBody(requestBodyLength);
                }

                // 读取目标服务器响应并转发给客户端
                int responseBodyLength = sendResponseHeader();

                // 发送响应体（如果有的话）
                if (responseBodyLength > 0) {
                    sendResponseBody(responseBodyLength);
                }
            }
        }
        catch(IOException ignored) {}
        finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {}
        }
    }

    //用于获取服务器的信息
    @Override
    public void getServerInfo() throws IOException {
        // 读取客户端请求
        String requestLine = null;
        try {
            requestLine = clientInput.readLine();
        } catch (SocketTimeoutException ignored) {}
        if (requestLine != null) {
            String[] requestParts = requestLine.split(" ");
            method = requestParts[0]; // 请求方法
            url = requestParts[1]; // 请求 URL
            version = requestParts[2]; // HTTP 版本

            // 将 URL 转换为 URL 对象
            URL targetUrl = null;
            try {
                targetUrl = new URL(url);
            } catch (MalformedURLException ignored) {}
            if (targetUrl == null) {
                return;
            }
            // 获取目标主机
            host = targetUrl.getHost();
            // 获取目标主机和端口
            port = targetUrl.getPort() == -1 ? (targetUrl.getProtocol().equals("https") ? 443 : 80) : targetUrl.getPort();

        }

    }

    //用于发送请求头
    //返回请求体的长度
    @Override
    public int sendRequestHeader() throws IOException {
        // 转发请求到目标服务器
        System.out.println("----------RequestHeader----------");
        serverOutput.println(method + " " + url + " " + version);
        System.out.println(method + " " + url + " " + version);

        // 读取并转发请求头
        String headerLine;
        int RequestBodyLength = -1; // 默认请求体长度为-1
        while ((headerLine = clientInput.readLine()) != null) {
            serverOutput.println(headerLine);
            System.out.println(headerLine);
            serverOutput.flush(); // 确保立即发送到目标服务器
            // 当读到空行时，说明请求头已经结束，可以开始读取请求体
            if (headerLine.isEmpty()) {
                break;
            }
            // 检查是否有 Content-Length 头部
            if (headerLine.toLowerCase().startsWith("content-length:")) {
                RequestBodyLength = Integer.parseInt(headerLine.split(": ")[1]);
            }
        }
        System.out.println("----------End----------");

        return RequestBodyLength;
    }

    // 用于发送请求体
    @Override
    public void sendRequestBody(int RequestBodyLength) throws IOException {
        // 读取并转发请求体
        if (RequestBodyLength > 0) {
            int totalBytesRead = 0; // 记录已读取的字节数
            char[] buffer = new char[4096]; // 用于读取响应体的缓冲区
            int bytesRead;

            System.out.println("----------RequestBody----------");
            // 继续读取，直到读取到的字节数大于或等于 Content-Length
            while (totalBytesRead < RequestBodyLength
                    && (bytesRead = clientInput.read(buffer, 0,
                    Math.min(buffer.length, RequestBodyLength - totalBytesRead))) != -1)
            {
                serverOutput.print(new String(buffer, 0, bytesRead)); // 转发读取的内容
                serverOutput.flush(); // 确保立即发送到服务端
                System.out.print(new String(buffer, 0, bytesRead)); // 打印到控制台
                totalBytesRead += bytesRead; // 更新已读取的字节数
            }
            System.out.println("EOF");
            System.out.println("----------End----------");
        }
    }


    //用于发送响应头
    //返回响应体的长度
    public int sendResponseHeader() throws IOException {
        // 读取目标服务器响应并转发给客户端
        String responseLine;
        int ResponseBodyLength = -1; // 默认响应体长度为-1
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
                ResponseBodyLength = Integer.parseInt(headerParts[1]);
            }
        }
        System.out.println("----------End----------");
        return ResponseBodyLength;
    }

    //用于发送响应体
    @Override
    public void sendResponseBody(int ResponseBodyLength) throws IOException {
        // 读取并转发响应体
        if (ResponseBodyLength > 0) {
            int totalBytesRead = 0; // 记录已读取的字节数
            char[] buffer = new char[4096]; // 用于读取响应体的缓冲区
            int bytesRead;

            System.out.println("----------ResponseBody----------");
            // 继续读取，直到读取到的字节数大于或等于 Content-Length
            while (totalBytesRead < ResponseBodyLength
                    && (bytesRead = serverInput.read(buffer, 0,
                    Math.min(buffer.length, ResponseBodyLength - totalBytesRead))) != -1)
            {
                clientOutput.print(new String(buffer, 0, bytesRead)); // 转发读取的内容
                clientOutput.flush(); // 确保立即发送到客户端
                System.out.print(new String(buffer, 0, bytesRead)); // 打印到控制台
                totalBytesRead += bytesRead; // 更新已读取的字节数
            }
            System.out.println("EOF");
            System.out.println("----------End----------");
        }
    }


}





