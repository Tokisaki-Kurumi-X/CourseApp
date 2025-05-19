package com.example.courseproject;

public interface ResponseListener {
    /** 成功回调，code 是 HTTP 状态码，body 是响应文本 */
    void onSuccess(int code, String body);
    /** 错误回调，e 是发生的异常 */
    void onError(Exception e);
}
