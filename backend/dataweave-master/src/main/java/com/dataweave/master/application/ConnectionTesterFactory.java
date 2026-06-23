package com.dataweave.master.application;

import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Factory that routes connection test requests to the appropriate {@link ConnectionTester}
 * based on the datasource type.
 */
@Service
public class ConnectionTesterFactory {

    private final List<ConnectionTester> testers;

    public ConnectionTesterFactory(List<ConnectionTester> testers) {
        this.testers = testers;
    }

    /**
     * Get the appropriate tester for the given datasource type.
     * Returns the first tester that supports the type, preferring specific over generic.
     */
    public ConnectionTester getTester(String typeCode) {
        return testers.stream()
                .filter(t -> t.supports(typeCode))
                // Prefer non-fallback testers (JdbcConnectionTester.supports returns true only for JDBC types)
                // UnsupportedConnectionTester.supports returns true for everything (fallback)
                .filter(t -> !(t instanceof com.dataweave.master.infrastructure.UnsupportedConnectionTester))
                .findFirst()
                .orElseGet(() -> testers.stream()
                        .filter(t -> t.supports(typeCode))
                        .findFirst()
                        .orElseThrow(() -> new BizException("datasource.test_unsupported", typeCode)));
    }

    /**
     * Test connectivity for a datasource (by ID).
     */
    public DatasourceDtos.ConnectionTestResult test(Datasource ds, String decryptedPassword) {
        ConnectionTester tester = getTester(ds.getTypeCode());
        return tester.test(ds, decryptedPassword);
    }
}
