package com.dataweave.master.application;

/**
 * 数据源密码解密失败异常。
 */
public class DatasourceDecryptException extends RuntimeException {

    public DatasourceDecryptException(String message) {
        super(message);
    }

    public DatasourceDecryptException(String message, Throwable cause) {
        super(message, cause);
    }
}
