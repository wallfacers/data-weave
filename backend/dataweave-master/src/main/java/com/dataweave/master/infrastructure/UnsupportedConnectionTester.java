package com.dataweave.master.infrastructure;

import com.dataweave.master.application.ConnectionTester;
import com.dataweave.master.application.DatasourceDtos.ConnectionTestResult;
import com.dataweave.master.domain.Datasource;
import org.springframework.stereotype.Component;

/**
 * Placeholder connection tester for non-JDBC datasource types (MongoDB, Redis, ES, etc.).
 * Returns a "not yet supported" message.
 */
@Component
public class UnsupportedConnectionTester implements ConnectionTester {

    @Override
    public boolean supports(String typeCode) {
        return true; // fallback — supports everything (lowest priority)
    }

    @Override
    public ConnectionTestResult test(Datasource ds, String decryptedPassword) {
        String typeName = ds.getTypeCode();
        return new ConnectionTestResult(
                false,
                typeName + " 连通性测试暂未实现，请手动确认连接参数",
                0,
                null
        );
    }
}
