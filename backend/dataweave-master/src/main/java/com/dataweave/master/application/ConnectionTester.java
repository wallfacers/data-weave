package com.dataweave.master.application;

import com.dataweave.master.application.DatasourceDtos.ConnectionTestResult;
import com.dataweave.master.domain.Datasource;

/**
 * Strategy interface for testing datasource connectivity.
 * Different implementations for JDBC, MongoDB, Redis, etc.
 */
public interface ConnectionTester {

    /**
     * Test connectivity to a datasource.
     *
     * @param ds the datasource entity (password_enc may still be encrypted)
     * @param decryptedPassword the decrypted password (null if no password)
     * @return test result with success/failure, message, latency, server version
     */
    ConnectionTestResult test(Datasource ds, String decryptedPassword);

    /**
     * Check if this tester supports the given datasource type.
     */
    boolean supports(String typeCode);
}
