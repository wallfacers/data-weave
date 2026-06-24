package com.dataweave.master.application;

import com.dataweave.master.application.DatasourceDtos.ConnectionTestResult;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.i18n.BizException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

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
                .filter(t -> !(t instanceof com.dataweave.master.infrastructure.UnsupportedConnectionTester))
                .findFirst()
                .orElseGet(() -> testers.stream()
                        .filter(t -> t.supports(typeCode))
                        .findFirst()
                        .orElseThrow(() -> new BizException("datasource.test_unsupported", typeCode)));
    }

    /** Test connectivity for a datasource (by ID), default Chinese locale. */
    public ConnectionTestResult test(Datasource ds, String decryptedPassword) {
        return getTester(ds.getTypeCode()).test(ds, decryptedPassword);
    }

    /** Test connectivity for a datasource, localizing result message by the given locale. */
    public ConnectionTestResult test(Datasource ds, String decryptedPassword, Locale locale) {
        return getTester(ds.getTypeCode()).test(ds, decryptedPassword, locale);
    }
}
