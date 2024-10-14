package proxy;

import java.io.*;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class CacheProxy implements Runnable{
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
    protected List<String> requestHeaders = new ArrayList<String>();
    protected List<String> responseHeaders = new ArrayList<String>();
    protected String responseBody;
    protected final String CACHE_PATH = "src/main/resources/Cache/";
    protected String cacheFile;
    protected int TIMEOUT = 30000; // 超时时间为30秒

    public CacheProxy(Socket clientSocket) {
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
            try (Socket serverSocket = new Socket(host, port);
                 PrintWriter serverOutput = new PrintWriter(serverSocket.getOutputStream(), true);
                 BufferedReader serverInput = new BufferedReader(new InputStreamReader(serverSocket.getInputStream(), StandardCharsets.UTF_8))) {

                System.out.println("=====连接到目标服务器: " + host + ":" + port + " =====");
                System.out.println("=====URL: " + url + " =====");
                this.serverOutput = serverOutput;
                this.serverInput = serverInput;
                serverSocket.setSoTimeout(TIMEOUT); // 设置超时

                if(method.equals("GET"))
                {
                    getRequestHeaders();// 读取请求头
                    // 此时已经分析得到了服务器的信息，而且生成了缓存文件名
                    // 先检查缓存
                    if(checkCache())
                    {
                        System.out.println("!!!=====缓存命中: " + cacheFile + " =====!!!");
                        // 缓存命中，需要向客户端发送请求，添加if-modified-since头
                        sendIfModifiedSinceHeader();
                        // 读取服务器响应
                        // 若返回304 Not Modified 即返回值为-1，则缓存有效，将缓存发送给客户端
                        // 若返回200 OK，即返回值大于0,则缓存无效，将服务器响应发送给客户端，并更新缓存
                        int ResponseBodyLength = getServerResponseHeader();
                        if(ResponseBodyLength==-1)
                        {
                            System.out.println("!!!=====缓存有效: " + cacheFile + " =====!!!");
                            // 缓存有效，发送缓存给客户端
                            // 但要注意缓存文件中Date需要更新
                            sendCachedResponse();
                            System.out.println("!!!=====缓存发送成功: " + cacheFile + " =====!!!");
                        }
                        else
                        {
                            System.out.println("!!!=====缓存无效: " + cacheFile + " =====!!!");
                            // 缓存无效，需要将服务器响应发送给客户端，并更新缓存
                            // 先发送响应头，即发送responseHeaders列表
                            sendServerResponseHeader();
                            // 然后再接收并发送响应体
                            receiveAndSendServerResponseBody(ResponseBodyLength);
                            // 此时响应头和响应体都在变量中，因此可以更新缓存
                            updateCacheFile();
                            System.out.println("!!!=====缓存更新: " + cacheFile + " =====!!!");

                        }
                    }
                    else // 缓存未命中，则直接向服务器发送请求
                    {
                        System.out.println("=====缓存未命中: " + cacheFile + " =====");
                        // 发送请求头 因为是GET请求，所以不需要发送请求体
                        sendRequestHeaders();
                        // 接下来接收服务器的响应头和响应体，发送并保存即可
                        // 接收服务器响应头并保存
                        int ResponseBodyLength = getServerResponseHeader();
                        // 发送响应头
                        sendServerResponseHeader();
                        // 接收并发送服务器响应体并保存
                        receiveAndSendServerResponseBody(ResponseBodyLength);
                        // 更新缓存
                        updateCacheFile();
                        System.out.println("=====缓存更新: " + cacheFile + " =====");
                    }

                }
                else // 处理POST请求
                {
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
        }
        catch(IOException e) {
            e.printStackTrace();}
        finally
        {
            try
            {
                clientSocket.close();
            } catch (IOException ignored) {}
        }
    }

    public void getServerInfo() throws IOException {
        // 读取客户端请求
        String requestLine = null;
        try {
            requestLine = clientInput.readLine();
            System.out.println("第一行：" +requestLine);
        } catch (SocketTimeoutException ignored) {}
        if (requestLine != null) {
            String[] requestParts = requestLine.split(" ");
            method = requestParts[0]; // 请求方法
            url = requestParts[1]; // 请求 URL
            version = requestParts[2]; // HTTP 版本

            // 解析 URL
            // 这里只处理 http 和 https 协议，其他协议直接返回
            if (!url.startsWith("http://"))
            {
                url = "https://" + url;
            }

            // 缓存文件名
            cacheFile = CACHE_PATH + generateCacheFileName(url)+".txt";

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
            System.out.println("目标主机：" + host);
            // 获取目标主机和端口
            port = targetUrl.getPort() == -1 ? (targetUrl.getProtocol().equals("https") ? 443 : 80) : targetUrl.getPort();
            System.out.println("目标端口：" + port);
        }
    }

    // 生成缓存文件名
    public String generateCacheFileName(String url) {
        if (url != null) {
            // 去除前缀
            String fileName = url.replaceAll("^(https?://)?", ""); // 移除 http:// 或 https://

            // 替换非法字符
            fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");

            return fileName;
        }
        return null;
    }

    // 读取请求头到 requestHeaders 列表中
    // GET请求使用
    // 不包括空行，发送时需手动添加！
    public void getRequestHeaders() throws IOException
    {
        System.out.println("----------RequestHeader----------");
        requestHeaders.add(method + " " + url + " " + version);
        System.out.println(method + " " + url + " " + version);

        String headerLine;
        while ((headerLine = clientInput.readLine()) != null && !headerLine.isEmpty()) {
            // 这里需要跳过If-Modified-Since头和If-None-Match头，因为它是由客户端添加的，不应该在缓存中查找
            if (!headerLine.startsWith("If-Modified-Since") && !headerLine.startsWith("If-None-Match")) {
                requestHeaders.add(headerLine);
                System.out.println(headerLine);
            }
        }
        System.out.println("----------End----------");
    }

    // 检查缓存
    public boolean checkCache()
    {
        File f = new File(cacheFile);
        return f.exists();
    }

    // 向客户端发送请求，添加if-modified-since头
    public void sendIfModifiedSinceHeader() throws IOException {
        System.out.println("----------SendingIfModifiedSinceHeader----------");
        serverOutput.println(requestHeaders.get(0));
        System.out.println(requestHeaders.get(0));
        serverOutput.println(requestHeaders.get(1));
        System.out.println(requestHeaders.get(1));

        // 从cacheFile中读取If-Modified-Since头
        String ifModifiedSince = readIfModifiedSinceFromCacheFile();
        if (ifModifiedSince != null) {
            serverOutput.println("If-modified-since: " + ifModifiedSince);
            System.out.println("If-modified-since: " + ifModifiedSince);
        } else {
            System.out.println("No If-modified-since header found in cache file.");
        }

        serverOutput.println();// 结束请求头
        System.out.println("----------End----------");
    }

    // 从cacheFile读取If-Modified-Since头
    private String readIfModifiedSinceFromCacheFile() {
        File cacheFileObj = new File(cacheFile);
        if (!cacheFileObj.exists()) {
            System.out.println("Cache file does not exist.");
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(cacheFileObj))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Last-Modified")) {
                    String[] headerParts = line.split(": ", 2); // 只分割成两个部分
                    return headerParts[1];
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null; // 如果未找到返回null
    }

    // 读取服务器请求的响应头
    // 注意没有最后的空行，需手动添加！
    // 返回-1表示Not Modified或者没有响应体，返回大于0的数为响应体长度
    public int getServerResponseHeader() throws IOException {
        String responseLine;
        int ResponseBodyLength = -1;
        // 读取响应头
        System.out.println("----------ResponseHeader----------");
        while ((responseLine = serverInput.readLine()) != null && !responseLine.isEmpty()) {
            responseHeaders.add(responseLine);
            System.out.println(responseLine);

            String[] headerParts = responseLine.split(": ", 2); // 只分割成两个部分
            if (headerParts[0].equalsIgnoreCase("Content-Length")) {
                ResponseBodyLength = Integer.parseInt(headerParts[1]);
            }
        }
        System.out.println("----------End----------");
        return ResponseBodyLength;
    }

    // 发送服务器的响应头
    // 只在缓存无效或者未命中时直接发送
    public void sendServerResponseHeader() throws IOException {
        for (String headerLine : responseHeaders) {
            clientOutput.println(headerLine);
        }
        clientOutput.println(); // 结束响应头
    }

    // 接收并发送服务器的响应体
    // 需要保存在responseBody变量中
    public void receiveAndSendServerResponseBody(int ResponseBodyLength) throws IOException {
        // 读取并转发响应体
        if (ResponseBodyLength > 0) {
            int totalBytesRead = 0; // 记录已读取的字节数
            char[] buffer = new char[8192]; // 用于读取响应体的缓冲区
            int bytesRead;
            StringBuilder responseBodyBuilder = new StringBuilder();
            System.out.println("----------ResponseBody----------");
            System.out.println("长度为: " + ResponseBodyLength);

            try {
                // 继续读取，直到读取到的字节数大于或等于 Content-Length
                while (totalBytesRead < ResponseBodyLength
                        && (bytesRead = serverInput.read(buffer, 0, Math.min(buffer.length, ResponseBodyLength - totalBytesRead))) != 0) {
                    clientOutput.print(new String(buffer, 0, bytesRead)); // 转发读取的内容
                    clientOutput.flush(); // 确保立即发送到客户端
                    System.out.print("读取字节数: " + bytesRead); // 打印每次读取的字节数

                    // 记录到 StringBuilder 中
                    responseBodyBuilder.append(new String(buffer, 0, bytesRead));
                    totalBytesRead += bytesRead; // 更新已读取的字节数
                    System.out.println(" 已读取总字节数: " + totalBytesRead); // 打印已读取的字节数
                }
            } catch (IOException e) {
                System.out.println("读取的响应体中有中文字符......");
            } finally {
                System.out.println(".................EOF");
                System.out.println("----------End----------");

                // 记录响应体
                responseBody = responseBodyBuilder.toString();
            }
        }
    }


    // 更新缓存，即将服务器的响应头和响应体写入缓存文件cacheFile中
    public void updateCacheFile() {
        File file = new File(cacheFile);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            // 写入响应头
            for (String header : responseHeaders) {
                writer.write(header);
                writer.newLine(); // 写入新行
            }
            // 写入空行以分隔头部和主体
            writer.newLine();
            // 写入响应体
            if (responseBody != null) {
                writer.write(responseBody);
            }
        } catch (IOException e) { System.out.println("未找到目录！！！！！"); }
    }

    // 发送缓存的响应给客户端
    public void sendCachedResponse() {
        File file = new File(cacheFile);

        // 读取缓存文件并发送给客户端
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            boolean isResponseBody = false;

            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // 遇到空行后，表示响应头结束，发送一个空行
                    clientOutput.println();
                    isResponseBody = true; // 设置为读取响应体
                    break;
                }

                // 更新 Date 首部
                if (line.startsWith("Date:")) {
                    String currentDate = new java.util.Date().toString();
                    clientOutput.println("Date: " + currentDate); // 发送更新后的 Date
                } else {
                    clientOutput.println(line); // 发送其他响应头
                }
            }

            // 发送响应体（如果有的话）
            if (isResponseBody) {
                while ((line = reader.readLine()) != null) {
                    clientOutput.println(line); // 直接发送响应体
                }
            }
        } catch (IOException ignored) {}
    }

    // 发送请求头
    // 在缓存未命中时使用
    public void sendRequestHeaders() throws IOException {
        for (String headerLine : requestHeaders) {
            serverOutput.println(headerLine);
        }
        serverOutput.println(); // 结束请求头
    }

    // 以下方法用于处理一般请求

    //用于发送请求头
    //返回请求体的长度
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
