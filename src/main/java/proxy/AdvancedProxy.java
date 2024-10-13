package proxy;

import org.w3c.dom.*;

import java.io.*;
import java.net.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class AdvancedProxy extends CacheProxy{
    private final String user;
    private final List<String> allowedHosts = new ArrayList<String>();
    private final List<String> allowedUsers = new ArrayList<String>();
    private List<String> fishingHosts = new ArrayList<String>();


    /**
     * 构造函数
     * @param clientSocket 客户端 Socket
     * @param user 用户
     */
    public AdvancedProxy(Socket clientSocket,String user) {
        super(clientSocket);
        String configFilePath = "src/main/resources/config.xml";
        loadConfiguration(configFilePath);
        this.user = user;
    }

    /**
     * 构造函数，默认用户为 admin
     * @param clientSocket 客户端 Socket
     */
    public AdvancedProxy(Socket clientSocket) {
        super(clientSocket);
        String configFilePath = "src/main/resources/config.xml";
        loadConfiguration(configFilePath);
        this.user = "admin";
    }

    // 加载配置文件
    private void loadConfiguration(String filePath) {
        try {
            // 创建文档构建器
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();

            // 使用 InputStream 从资源文件加载 XML
            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("config.xml");
            if (inputStream == null) {
                System.err.println("Unable to find file: " + filePath);
                return;
            }

            // 解析 XML 文件
            Document document = builder.parse(inputStream);
            document.getDocumentElement().normalize();

            // 加载所有的 website 节点
            NodeList websiteNodeList = document.getElementsByTagName("website");
            for (int i = 0; i < websiteNodeList.getLength(); i++) {
                Node node = websiteNodeList.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;
                    allowedHosts.add(element.getTextContent());
                }
            }

            // 加载所有的 user 节点
            NodeList userNodeList = document.getElementsByTagName("user");
            for (int i = 0; i < userNodeList.getLength(); i++) {
                Node node = userNodeList.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;
                    allowedUsers.add(element.getTextContent());
                }
            }

            // 加载所有的 fishing 节点
            NodeList fishingNodeList = document.getElementsByTagName("fish");
            for (int i = 0; i < fishingNodeList.getLength(); i++) {
                Node node = fishingNodeList.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;
                    fishingHosts.add(element.getTextContent());
                }
            }
        } catch (Exception ignored) {}
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
            // 判断是否允许访问
            if(!isAllowed()&&host!=null)
            {
                System.err.println("!!!***** "+user+" 不允许访问 "+host+" *****!!!");
                return;
            }
            if(host==null)
            {
                return;
            }
            // 判断是否是钓鱼网站
            if(fishing())
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
        finally {
            try {
                clientSocket.close();
            } catch (IOException ignored) {}
        }
    }

    // 判断当前类是否允许访问
    private boolean isAllowed()
    {
        // 只接收GET和POST请求
        if(method == null || (!method.equals("GET") && !method.equals("POST")))
        {
            return false;
        }
        // 只接收HTTP协议
        if(host == null ||port!=80)
        {
            return false;
        }
        boolean f1 = false;
        boolean f2 = false;
        // 允许访问的网站
        for(String allowedHost : allowedHosts)
        {
            if (host.equals(allowedHost)) {
                f1 = true;
                break;
            }
        }
        // 允许访问的用户
        for(String allowedUser : allowedUsers)
        {
            if(user.equals(allowedUser)) {
                f2 = true;
                break;
            }
        }

        return f1 && f2;
    }

    // 发送钓鱼网站
    private boolean fishing()
    {
        boolean ret = false;
        // 判断host是否在钓鱼网站列表中
        for(String fishingHost : fishingHosts)
        {
            if(host.equals(fishingHost))
            {
                ret = true;
                break;
            }
        }

        if (ret) {
            try {
                System.out.println("!!!***** "+user+"正在被"+host+"钓鱼 *****!!!");
                // 读取重定向响应报文
                List<String> redirectResponse = Files.readAllLines(Paths.get("src/main/resources/fish.txt"), StandardCharsets.UTF_8);

                // 发送每一行到客户端
                for (String line : redirectResponse) {
                    clientOutput.println(line);
                }
                clientOutput.flush(); // 确保所有数据都被发送
                System.out.println("!!!***** "+user+" 已被钓鱼 *****!!!");
            } catch (IOException e) {
                System.err.println("发送重定向响应时出错: " + e.getMessage());
            }
        }
        return ret;
    }

}
