package proxy;

import java.io.IOException;

public interface Proxy {
    /**
     * 用于获取服务器信息
     */
    public void getServerInfo() throws Exception;

    /**
     * 用于发送请求头
     * @return 请求体长度
     */
    public int sendRequestHeader() throws Exception;

    /**
     * 用于发送请求体
     * @param RequestBodyLength 请求体长度
     */
    public void sendRequestBody(int RequestBodyLength) throws Exception;

    /**
     * 用于获取响应头
     * @return 响应体长度
     */
    public int sendResponseHeader() throws Exception;

    /**
     * 用于获取响应体
     * @param ResponseBodyLength 响应体长度
     */
    public void sendResponseBody(int ResponseBodyLength) throws Exception;
}
