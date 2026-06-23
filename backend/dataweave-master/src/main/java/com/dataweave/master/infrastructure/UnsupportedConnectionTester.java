package com.dataweave.master.infrastructure;

import com.dataweave.master.application.ConnectionTester;
import com.dataweave.master.application.DatasourceDtos.ConnectionTestResult;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.i18n.Messages;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Placeholder connection tester for non-JDBC datasource types (MongoDB, Redis, ES, etc.).
 * Returns a "not yet supported" message, localized by locale (i18n 规则②).
 */
@Component
public class UnsupportedConnectionTester implements ConnectionTester {

    private final Messages messages;

    public UnsupportedConnectionTester(Messages messages) {
        this.messages = messages;
    }

    @Override
    public boolean supports(String typeCode) {
        return true; // fallback — supports everything (lowest priority)
    }

    @Override
    public ConnectionTestResult test(Datasource ds, String decryptedPassword, Locale locale) {
        return new ConnectionTestResult(
                false,
                messages.get("datasource.test.unsupported", locale, ds.getTypeCode()),
                0,
                null
        );
    }
}
